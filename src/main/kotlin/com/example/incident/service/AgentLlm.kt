package com.example.incident.service

import com.example.incident.domain.Incident

/**
 * ReAct full / function calling 型のループに対応する LLM 抽象。
 * 1-shot の LlmConnector とは別 interface にして用途を明示分離する。
 */
interface AgentLlm {
    /**
     * 過去の会話履歴 (history) を見て、次に取るべきアクションを返す。
     * - Final: 最終回答を確定
     * - CallTool: 指定 tool を呼んでほしい (実行は呼出元で)
     */
    suspend fun decide(
        incident: Incident,
        history: List<AgentTurn>,
    ): AgentDecision
}

/** 会話 1 ターン (user / assistant / tool_result のいずれか) */
data class AgentTurn(
    val role: Role,
    val text: String? = null,
    val toolUseId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolResult: String? = null,
) {
    enum class Role { USER, ASSISTANT, TOOL_RESULT }
}

sealed class AgentDecision {
    data class Final(
        val analysis: LlmAnalysis,
    ) : AgentDecision()

    data class CallTool(
        val toolUseId: String,
        val toolName: String,
        val toolInput: String, // JSON string
    ) : AgentDecision()
}
