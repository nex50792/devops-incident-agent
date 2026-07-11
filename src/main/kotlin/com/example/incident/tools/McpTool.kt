package com.example.incident.tools

import com.example.incident.mcp.McpClient
import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 外部 MCP サーバーが提供する tool を Tool interface でラップする。
 * codebase_tour / convention_extract / extract_symbols の 3 種類を包む。
 */
class McpTool(
    override val name: String,
    override val description: String,
    override val parameters: Schema,
    private val mcp: McpClient?,
) : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(inputJson: String): String {
        val client = mcp ?: return """{"content":[{"type":"text","text":"<no mcp>"}]}"""
        val args = json.parseToJsonElement(inputJson).jsonObject
        val raw = client.callTool(name, args) as? JsonObject ?: return "{}"
        return raw.toString()
    }

    companion object {
        fun codebaseTour(mcp: McpClient?): McpTool =
            McpTool(
                name = "codebase_tour",
                description = "Return top-N most-important files (entrypoints / configs / hot files).",
                parameters =
                    Schema
                        .builder()
                        .type(Type.Known.OBJECT)
                        .properties(
                            mapOf(
                                "path" to Schema.builder().type(Type.Known.STRING).build(),
                                "top_n" to Schema.builder().type(Type.Known.INTEGER).build(),
                            ),
                        ).required(listOf("path"))
                        .build(),
                mcp = mcp,
            )

        fun conventionExtract(mcp: McpClient?): McpTool =
            McpTool(
                name = "convention_extract",
                description = "Return naming conventions and test strategy of the project.",
                parameters =
                    Schema
                        .builder()
                        .type(Type.Known.OBJECT)
                        .properties(
                            mapOf(
                                "path" to Schema.builder().type(Type.Known.STRING).build(),
                            ),
                        ).required(listOf("path"))
                        .build(),
                mcp = mcp,
            )

        fun extractSymbols(mcp: McpClient?): McpTool =
            McpTool(
                name = "extract_symbols",
                description = "Return top-level symbols (functions/classes/structs) per file via tree-sitter.",
                parameters =
                    Schema
                        .builder()
                        .type(Type.Known.OBJECT)
                        .properties(
                            mapOf(
                                "path" to Schema.builder().type(Type.Known.STRING).build(),
                                "max_files" to Schema.builder().type(Type.Known.INTEGER).build(),
                            ),
                        ).required(listOf("path"))
                        .build(),
                mcp = mcp,
            )
    }
}
