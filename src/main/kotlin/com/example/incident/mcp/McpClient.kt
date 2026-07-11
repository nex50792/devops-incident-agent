package com.example.incident.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * stdio transport で MCP server プロセスとやりとりする最小 Client。
 * 公式 Kotlin SDK が 2026-04 時点で存在しないため、JSON-RPC 2.0 を直接実装する。
 *
 * 使い方:
 *   val client = McpClient(listOf("/path/to/mcp-server"))
 *   client.start()
 *   val tools = client.listTools()
 *   val result = client.callTool("codebase_tour", buildJsonObject { put("path", "/repo"); put("top_n", 5) })
 *   client.shutdown()
 */
class McpClient(
    private val command: List<String>,
) {
    private val log = LoggerFactory.getLogger(McpClient::class.java)
    private val nextId = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    suspend fun start() =
        withContext(Dispatchers.IO) {
            require(process == null) { "Already started" }
            val proc =
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))

            sendRequest(
                method = "initialize",
                params =
                    buildJsonObject {
                        put("protocolVersion", "2024-11-05")
                        put(
                            "clientInfo",
                            buildJsonObject {
                                put("name", "devops-incident-agent")
                                put("version", "0.0.1")
                            },
                        )
                        put("capabilities", buildJsonObject {})
                    },
            )

            sendNotification(method = "notifications/initialized", params = buildJsonObject {})
            log.info("MCP handshake completed: command={}", command.joinToString(" "))
        }

    suspend fun listTools(): JsonElement = sendRequest(method = "tools/list", params = buildJsonObject {})

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): JsonElement =
        sendRequest(
            method = "tools/call",
            params =
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
        )

    suspend fun shutdown() =
        withContext(Dispatchers.IO) {
            try {
                writer?.close()
                reader?.close()
                process?.destroy()
                process?.waitFor()
            } finally {
                process = null
                writer = null
                reader = null
            }
        }

    private suspend fun sendRequest(
        method: String,
        params: JsonObject,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val id = nextId.getAndIncrement()
            val request =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }
            writeLine(request.toString())
            val responseLine =
                reader?.readLine()
                    ?: throw IllegalStateException("MCP server closed connection during $method")
            val response = json.parseToJsonElement(responseLine).jsonObject
            // JSON-RPC 2.0: error or result が片方だけ入る
            response["error"]?.let { throw McpError(it.toString()) }
            response["result"] ?: throw IllegalStateException("Missing result in response: $responseLine")
        }

    private suspend fun sendNotification(
        method: String,
        params: JsonObject,
    ) = withContext(Dispatchers.IO) {
        val notification =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
        writeLine(notification.toString())
    }

    private fun writeLine(line: String) {
        val w = writer ?: throw IllegalStateException("Client not started")
        w.write(line)
        w.newLine()
        w.flush()
    }
}

class McpError(
    message: String,
) : RuntimeException(message)

private val JsonElement.jsonObject: JsonObject
    get() = this as JsonObject
