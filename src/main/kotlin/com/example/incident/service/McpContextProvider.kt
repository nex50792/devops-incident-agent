package com.example.incident.service

import com.example.incident.mcp.McpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

data class McpContext(
    val files: List<String>,
    val rawSnippet: String,
)

data class ConventionContext(
    val rawSnippet: String,
)

interface McpContextProvider {
    suspend fun fetchTour(
        repoPath: String,
        topN: Int = 5,
    ): McpContext

    suspend fun fetchConvention(repoPath: String): ConventionContext
}

/**
 * McpClient (実プロセス) を持たない / MCP サーバー未設定の環境でも動作するためのデフォルト実装。
 * 注入された McpClient が start 失敗する場合、空 context を返す (incident 解析全体は継続)。
 */
class McpClientContextProvider(
    private val client: McpClient,
) : McpContextProvider {
    private val log = LoggerFactory.getLogger(McpClientContextProvider::class.java)

    override suspend fun fetchTour(
        repoPath: String,
        topN: Int,
    ): McpContext {
        return try {
            val args =
                buildJsonObject {
                    put("path", repoPath)
                    put("top_n", topN)
                }
            val raw = client.callTool("codebase_tour", args) as? JsonObject ?: return McpContext(emptyList(), "")
            val files = extractFiles(raw)
            McpContext(files, summarize(files))
        } catch (e: Exception) {
            log.warn("MCP fetchTour failed (returning empty context): {}", e.message)
            McpContext(emptyList(), "")
        }
    }

    override suspend fun fetchConvention(repoPath: String): ConventionContext {
        return try {
            val args = buildJsonObject { put("path", repoPath) }
            val raw = client.callTool("convention_extract", args) as? JsonObject ?: return ConventionContext("")
            ConventionContext(extractText(raw).take(800))
        } catch (e: Exception) {
            log.warn("MCP fetchConvention failed (returning empty): {}", e.message)
            ConventionContext("")
        }
    }

    private fun extractFiles(result: JsonObject): List<String> {
        val text = extractText(result)
        return Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .take(10)
            .toList()
    }

    private fun extractText(result: JsonObject): String {
        val content = result["content"]?.jsonArray ?: return ""
        return content
            .firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content ?: ""
    }

    private fun summarize(files: List<String>): String =
        if (files.isEmpty()) "" else "MCP codebase_tour 上位ファイル: " + files.joinToString(", ")
}

/** テスト用 / MCP サーバー未設定時のフォールバック */
class NullMcpContextProvider : McpContextProvider {
    override suspend fun fetchTour(
        repoPath: String,
        topN: Int,
    ): McpContext = McpContext(emptyList(), "")

    override suspend fun fetchConvention(repoPath: String): ConventionContext = ConventionContext("")
}
