package com.example.incident.config

import com.example.incident.mcp.McpClient
import com.example.incident.service.AgentLlm
import com.example.incident.service.GeminiAgentLlm
import com.example.incident.service.GeminiLlmConnector
import com.example.incident.service.IncidentAnalysisService
import com.example.incident.service.IncidentStore
import com.example.incident.service.LlmConnector
import com.example.incident.service.McpClientContextProvider
import com.example.incident.service.McpContextProvider
import com.example.incident.service.NullMcpContextProvider
import com.example.incident.service.ReActAgentService
import com.example.incident.service.StubAgentLlm
import com.example.incident.service.StubLlmConnector
import com.example.incident.tools.McpTool
import com.example.incident.tools.QueryLogsTool
import com.example.incident.tools.SearchPostmortemTool
import com.example.incident.tools.SearchRecentChangesTool
import com.example.incident.tools.Tool
import com.example.incident.tools.ToolRegistry
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryOptions
import com.google.genai.Client
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

@Configuration
class IncidentConfig(
    @Value("\${incident.mcp.command:#{null}}") private val mcpCommand: String?,
    @Value("\${incident.repo-path:.}") private val repoPath: String,
    @Value("\${incident.llm.gemini.api-key:#{null}}") private val geminiApiKey: String?,
    @Value("\${incident.llm.gemini.model:gemini-2.5-flash}") private val geminiModel: String,
    @Value("\${incident.bigquery.project-id:#{null}}") private val bqProjectId: String?,
    @Value("\${incident.bigquery.dataset:logs}") private val bqDataset: String,
    @Value("\${incident.bigquery.table:run_googleapis_com_stdout}") private val bqTable: String,
    @Value("\${incident.github.token:#{null}}") private val githubToken: String?,
    @Value("\${incident.github.default-repo:#{null}}") private val githubDefaultRepo: String?,
) {
    private val log = LoggerFactory.getLogger(IncidentConfig::class.java)

    @Volatile
    private var managedClient: McpClient? = null

    @Bean
    @ConditionalOnMissingBean
    fun llmConnector(): LlmConnector {
        val key = geminiApiKey?.trim().orEmpty()
        if (key.isEmpty()) {
            log.info("incident.llm.gemini.api-key 未設定のため Stub LLM を使用 (実呼出なし)")
            return StubLlmConnector()
        }
        log.info("Gemini LLM connector を有効化: model={}", geminiModel)
        return GeminiLlmConnector(apiKey = key, model = geminiModel)
    }

    @Bean
    @ConditionalOnMissingBean
    fun mcpContextProvider(): McpContextProvider {
        val cmd = mcpCommand?.trim().orEmpty()
        if (cmd.isEmpty()) {
            log.info("incident.mcp.command 未設定のため Null context provider を使用")
            return NullMcpContextProvider()
        }
        if (!Files.exists(Paths.get(cmd))) {
            log.warn("MCP binary が見つかりません ({}); Null provider にフォールバック", cmd)
            return NullMcpContextProvider()
        }
        val client = McpClient(listOf(cmd))
        return try {
            runBlocking { client.start() }
            managedClient = client
            log.info("MCP client started: {}", cmd)
            McpClientContextProvider(client)
        } catch (e: Exception) {
            log.error("MCP start failed; Null provider にフォールバック: {}", e.message)
            NullMcpContextProvider()
        }
    }

    @Bean
    fun incidentStore(): IncidentStore = IncidentStore()

    @Bean
    fun incidentAnalysisService(
        provider: McpContextProvider,
        llm: LlmConnector,
        meterRegistry: MeterRegistry,
        store: IncidentStore,
    ): IncidentAnalysisService = IncidentAnalysisService(provider, llm, repoPath, meterRegistry, store)

    // getDefaultInstance は GCP 環境情報 (ADC) から project を推測しようとして
    // 見つからないと起動自体が失敗するため、project-id 設定時のみ bean を生成する
    @Bean
    @ConditionalOnProperty(name = ["incident.bigquery.project-id"])
    fun bigQuery(): BigQuery =
        BigQueryOptions
            .newBuilder()
            .setProjectId(requireNotNull(bqProjectId).trim())
            .build()
            .service

    @Bean
    fun geminiClient(): Client {
        val key = geminiApiKey?.trim().orEmpty()
        // key 未設定でも Client 生成できるようダミーを渡す (実 API 呼出時にエラー)
        // これは Postmortem embedding や tool 実行が config 時ではなく runtime 時に走るため。
        return Client.builder().apiKey(key.ifEmpty { "unset-placeholder-key" }).build()
    }

    @Bean
    @ConditionalOnProperty(name = ["spring.datasource.url"])
    fun searchPostmortemTool(
        client: Client,
        jdbc: JdbcTemplate,
    ): SearchPostmortemTool = SearchPostmortemTool(client, jdbc)

    @Bean
    fun toolRegistry(
        bigQuery: BigQuery?,
        postmortemTool: SearchPostmortemTool?,
    ): ToolRegistry {
        val tools = mutableListOf<Tool>()

        // MCP tools (incident.mcp.command 設定時のみ動く、なければ <no mcp> を返す)
        tools += McpTool.codebaseTour(managedClient)
        tools += McpTool.conventionExtract(managedClient)
        tools += McpTool.extractSymbols(managedClient)

        // 外部 SaaS tools
        val project = bqProjectId?.trim().orEmpty()
        if (bigQuery != null && project.isNotEmpty()) {
            tools += QueryLogsTool(bigQuery, project, bqDataset, bqTable)
            log.info("Registered query_logs tool: {}.{}.{}", project, bqDataset, bqTable)
        } else {
            log.info("incident.bigquery.project-id 未設定のため query_logs tool を登録せず")
        }

        tools += SearchRecentChangesTool(githubToken, githubDefaultRepo)
        log.info("Registered search_recent_changes tool (github.default-repo={})", githubDefaultRepo ?: "<not set>")

        if (postmortemTool != null) {
            tools += postmortemTool
            log.info("Registered search_postmortem tool (pgvector)")
        } else {
            log.info("spring.datasource.url 未設定のため search_postmortem tool を登録せず")
        }

        return ToolRegistry(tools)
    }

    @Bean
    @ConditionalOnMissingBean
    fun agentLlm(registry: ToolRegistry): AgentLlm {
        val key = geminiApiKey?.trim().orEmpty()
        return if (key.isEmpty()) {
            log.info("incident.llm.gemini.api-key 未設定のため StubAgentLlm を使用")
            StubAgentLlm(repoPath = repoPath)
        } else {
            log.info("GeminiAgentLlm (ReAct full) を有効化 with {} tools", registry.names().size)
            GeminiAgentLlm(apiKey = key, registry = registry, model = geminiModel)
        }
    }

    @Bean
    fun reactAgentService(
        agent: AgentLlm,
        meterRegistry: MeterRegistry,
        registry: ToolRegistry,
        store: IncidentStore,
    ): ReActAgentService =
        ReActAgentService(
            mcpClient = managedClient,
            agent = agent,
            meterRegistry = meterRegistry,
            maxSteps = 5,
            toolRegistry = registry,
            store = store,
        )

    @PreDestroy
    fun closeMcp() {
        managedClient?.let {
            try {
                runBlocking { it.shutdown() }
            } catch (e: Exception) {
                log.warn("MCP shutdown error: {}", e.message)
            }
        }
    }
}
