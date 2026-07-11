package com.example.incident.tools

import com.google.genai.types.Schema

/**
 * agent が呼び出せる tool の抽象。
 * name / description / parameter schema (Gemini function calling 用) と、実行ロジックを持つ。
 *
 * 既存の MCP tool (codebase_tour / convention_extract / extract_symbols) と、
 * 新規追加の外部 SaaS tool (query_logs / search_recent_changes 等) を統一で扱う。
 */
interface Tool {
    val name: String
    val description: String
    val parameters: Schema

    /**
     * 入力 JSON 文字列を受け取って結果 JSON 文字列を返す。
     * agent 側 (LLM) に返す text として使われる。
     */
    suspend fun execute(inputJson: String): String
}

/**
 * name → Tool のマップ。ReActAgentService が invokeTool 時に、
 * GeminiAgentLlm が tool declaration 時に参照する。
 */
class ToolRegistry(
    tools: List<Tool>,
) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun names(): Set<String> = byName.keys

    fun all(): List<Tool> = byName.values.toList()
}
