package com.example.incident.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 外部 MCP サーバー binary と McpClient (Kotlin) の end-to-end 統合テスト。
 *
 * 環境変数 INCIDENT_MCP_TEST_BIN に codebase_tour / convention_extract / project_glossary
 * を提供する MCP サーバーの binary パスを指定した時のみ実行する。
 * 未指定なら skip (CI では常に skip)。
 *
 * 実行: INCIDENT_MCP_TEST_BIN=<path> ./gradlew test --tests "*McpClientIntegrationTest"
 */
class McpClientIntegrationTest {
    private val serverBin: String? = System.getenv("INCIDENT_MCP_TEST_BIN")

    private fun serverAvailable(): Boolean = serverBin != null && Files.exists(Paths.get(serverBin))

    private fun requireServerBin(): String {
        assumeTrue(serverAvailable(), "INCIDENT_MCP_TEST_BIN 未指定または binary 不在のため skip")
        return serverBin!!
    }

    @Test
    fun `tools list returns 3 expected tools`(): Unit =
        runBlocking {
            val client = McpClient(listOf(requireServerBin()))
            try {
                client.start()
                val result = client.listTools() as JsonObject
                val raw = result.toString()
                assertNotNull(result["tools"], "tools field missing in response")
                assertTrue(raw.contains("codebase_tour"), "codebase_tour not exposed")
                assertTrue(raw.contains("convention_extract"), "convention_extract not exposed")
                assertTrue(raw.contains("project_glossary"), "project_glossary not exposed")
            } finally {
                client.shutdown()
            }
        }

    @Test
    fun `codebase_tour tool returns content for project root`(): Unit =
        runBlocking {
            val client = McpClient(listOf(requireServerBin()))
            try {
                client.start()
                val args =
                    buildJsonObject {
                        put("path", System.getProperty("user.dir"))
                        put("top_n", 3)
                    }
                val result = client.callTool("codebase_tour", args) as JsonObject
                assertNotNull(result["content"], "content field missing in tool result")
            } finally {
                client.shutdown()
            }
        }

    @Test
    fun `convention_extract tool returns naming stats`(): Unit =
        runBlocking {
            val client = McpClient(listOf(requireServerBin()))
            try {
                client.start()
                val args =
                    buildJsonObject {
                        put("path", System.getProperty("user.dir"))
                    }
                val result = client.callTool("convention_extract", args) as JsonObject
                val raw = result.toString()
                assertTrue(raw.contains("file_naming") || raw.contains("dominant"), "naming stats missing: $raw")
            } finally {
                client.shutdown()
            }
        }
}
