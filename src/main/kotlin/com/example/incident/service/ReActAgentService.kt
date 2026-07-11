package com.example.incident.service

import com.example.incident.domain.Diagnosis
import com.example.incident.domain.Incident
import com.example.incident.domain.TraceStep
import com.example.incident.mcp.McpClient
import com.example.incident.tools.ToolRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * ReAct full ループ: LLM が tool 選択を判断、最大 maxSteps 回反復。
 *
 * 入力: incident
 * ループ: history を持って llm.decide() → CallTool / Final
 *  - CallTool: McpClient で tool 実行 → 結果を history に追加 → 次へ
 *  - Final:    そのまま Diagnosis を組み立てて return
 * 終端: maxSteps 超過時は強制 final fallback
 *
 * IncidentAnalysisService (ReAct lite) は判断ロジックがコードに固定だが、
 * 本クラスは LLM が動的にツールを選ぶ。代償として回数 / コスト / レイテンシは
 * 予測しづらい。max_steps と timeout でガードする。
 */
class ReActAgentService(
    private val mcpClient: McpClient?, // null なら mcp なし環境 (テスト用)
    private val agent: AgentLlm,
    private val meterRegistry: MeterRegistry,
    private val maxSteps: Int = 5,
    private val toolRegistry: ToolRegistry? = null,
    private val store: IncidentStore? = null,
) {
    private val log = LoggerFactory.getLogger(ReActAgentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(incident: Incident): Diagnosis {
        MDC.put("incident_id", incident.id.toString())
        MDC.put("severity", incident.severity.name)
        MDC.put("service", incident.service)
        val overall = Timer.start(meterRegistry)
        try {
            log.info("react.start: maxSteps={}", maxSteps)
            val trace = mutableListOf<TraceStep>()
            val history = mutableListOf<AgentTurn>()
            var step = 0
            var mcpFiles: List<String> = emptyList()

            while (step < maxSteps) {
                step++
                MDC.put("trace_step", step.toString())
                MDC.put("trace_kind", "agent:decide")

                val decideTimer = Timer.start(meterRegistry)
                val decision =
                    try {
                        agent.decide(incident, history)
                    } finally {
                        decideTimer.stop(meterRegistry.timer("agent.decide.duration"))
                    }

                when (decision) {
                    is AgentDecision.Final -> {
                        trace += TraceStep(step, "agent:final", "model=${decision.analysis.model}")
                        log.info("react.done: steps={} model={}", step, decision.analysis.model)
                        meterRegistry
                            .counter(
                                "react.completed",
                                "severity",
                                incident.severity.name,
                                "outcome",
                                "final",
                            ).increment()
                        val diagnosis =
                            Diagnosis(
                                incidentId = incident.id,
                                hypothesis = decision.analysis.hypothesis,
                                recommendations = decision.analysis.recommendations,
                                mcpContextFiles = mcpFiles,
                                llmModel = decision.analysis.model,
                                trace = trace,
                            )
                        store?.save(incident, diagnosis)
                        return diagnosis
                    }

                    is AgentDecision.CallTool -> {
                        trace += TraceStep(step, "agent:tool_use", "${decision.toolName}")
                        history +=
                            AgentTurn(
                                role = AgentTurn.Role.ASSISTANT,
                                toolUseId = decision.toolUseId,
                                toolName = decision.toolName,
                                toolInput = decision.toolInput,
                            )

                        val toolTimer = Timer.start(meterRegistry)
                        val result =
                            try {
                                invokeTool(decision.toolName, decision.toolInput)
                            } finally {
                                toolTimer.stop(
                                    meterRegistry.timer(
                                        "agent.tool.duration",
                                        "tool",
                                        decision.toolName,
                                    ),
                                )
                            }

                        val truncated = result.take(2000) + if (result.length > 2000) "..." else ""
                        history +=
                            AgentTurn(
                                role = AgentTurn.Role.TOOL_RESULT,
                                toolUseId = decision.toolUseId,
                                toolResult = truncated,
                            )
                        if (decision.toolName == "codebase_tour") {
                            mcpFiles = parseFiles(result)
                        }
                    }
                }
            }

            // maxSteps 超過: 履歴を渡して final 強制
            log.warn("react.max_steps: steps={} - forcing final", step)
            meterRegistry
                .counter(
                    "react.completed",
                    "severity",
                    incident.severity.name,
                    "outcome",
                    "max_steps",
                ).increment()
            val final =
                agent.decide(
                    incident,
                    history +
                        AgentTurn(
                            role = AgentTurn.Role.USER,
                            text = "max_steps reached, please finalize with what you have.",
                        ),
                )
            val analysis = (final as? AgentDecision.Final)?.analysis
            val diagnosis =
                if (analysis == null) {
                    Diagnosis(
                        incidentId = incident.id,
                        hypothesis = "max_steps reached without final answer",
                        recommendations = emptyList(),
                        mcpContextFiles = mcpFiles,
                        llmModel = "agent-fallback",
                        trace = trace + TraceStep(step + 1, "agent:max_steps", "no final"),
                    )
                } else {
                    Diagnosis(
                        incidentId = incident.id,
                        hypothesis = analysis.hypothesis,
                        recommendations = analysis.recommendations,
                        mcpContextFiles = mcpFiles,
                        llmModel = analysis.model,
                        trace = trace + TraceStep(step + 1, "agent:final_forced", "model=${analysis.model}"),
                    )
                }
            store?.save(incident, diagnosis)
            return diagnosis
        } finally {
            overall.stop(meterRegistry.timer("react.analyze.duration", "severity", incident.severity.name))
            MDC.remove("incident_id")
            MDC.remove("severity")
            MDC.remove("service")
            MDC.remove("trace_step")
            MDC.remove("trace_kind")
        }
    }

    private suspend fun invokeTool(
        name: String,
        inputJson: String,
    ): String {
        // 1. まず ToolRegistry (BigQuery / GitHub / pgvector 等) にある tool を優先
        toolRegistry?.get(name)?.let { tool ->
            return tool.execute(inputJson)
        }
        // 2. なければ MCP tool として呼び出す (codebase_tour / convention_extract / extract_symbols)
        val client = mcpClient ?: return """{"content":[{"type":"text","text":"<no mcp>"}]}"""
        val args = json.parseToJsonElement(inputJson).jsonObject
        val raw = client.callTool(name, args) as? JsonObject ?: return "{}"
        return raw.toString()
    }

    private fun parseFiles(toolResult: String): List<String> =
        Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(toolResult)
            .map { it.groupValues[1] }
            .take(10)
            .toList()
}
