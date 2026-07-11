package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation

data class LlmAnalysis(
    val hypothesis: String,
    val recommendations: List<Recommendation>,
    val model: String,
)

interface LlmConnector {
    suspend fun analyze(
        incident: Incident,
        mcpContextSnippet: String,
    ): LlmAnalysis
}

class LlmCallException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : RuntimeException(message, cause)

/**
 * google-genai SDK の例外を LlmCallException に変換する。
 * SDK は独自の ApiException 階層を投げるため、そのままでは RetryPolicy の
 * retry 判定 (message 中の status code 抽出) に乗らない。
 * MALFORMED_FUNCTION_CALL はモデル出力の一時的な崩れで、再試行で回復するため
 * retryable として変換する。
 */
suspend fun <T> wrapGeminiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: com.google.genai.errors.ApiException) {
        throw LlmCallException("Gemini API ${e.code()}: ${e.message()}", e)
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains("MALFORMED_FUNCTION_CALL") == true) {
            throw LlmCallException("Gemini malformed function call (transient)", e, retryable = true)
        }
        throw e
    }
