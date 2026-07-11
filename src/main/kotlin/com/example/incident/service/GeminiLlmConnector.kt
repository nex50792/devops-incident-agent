package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Google GenAI 公式 Java SDK (com.google.genai:google-genai) 経由で
 * Gemini API を叩く 1-shot Connector。
 *
 * 永久無料枠(15 RPM, 1M tokens/日) で運用するため、model は gemini-2.5-flash に固定。
 * responseMimeType = application/json + system instruction で JSON strict 出力を強制する。
 */
class GeminiLlmConnector(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash",
    private val clientOverride: Client? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
) : LlmConnector {
    private val log = LoggerFactory.getLogger(GeminiLlmConnector::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client: Client by lazy {
        clientOverride ?: Client.builder().apiKey(apiKey).build()
    }

    override suspend fun analyze(
        incident: Incident,
        mcpContextSnippet: String,
    ): LlmAnalysis =
        circuitBreaker.execute("gemini.analyze") {
            retryPolicy.execute("gemini.analyze") {
                wrapGeminiCall {
                    withContext(Dispatchers.IO) {
                        val config =
                            GenerateContentConfig
                                .builder()
                                .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_PROMPT)))
                                .responseMimeType("application/json")
                                .build()
                        val response =
                            client.models.generateContent(
                                model,
                                userPrompt(incident, mcpContextSnippet),
                                config,
                            )
                        val text = response.text() ?: throw LlmCallException("Gemini response text is null")
                        parseResponse(text)
                    }
                }
            }
        }

    private fun userPrompt(
        incident: Incident,
        mcpContextSnippet: String,
    ): String =
        buildString {
            append("以下のインシデント情報を分析してください。\n\n")
            append("- タイトル: ${incident.title}\n")
            append("- 詳細: ${incident.description}\n")
            append("- Severity: ${incident.severity}\n")
            append("- Service: ${incident.service}\n")
            if (mcpContextSnippet.isNotBlank()) {
                append("\n# 関連コードベースの情報 (MCP)\n")
                append(mcpContextSnippet)
                append("\n")
            }
            append("\n以下の JSON schema で回答してください:\n")
            append(
                """{"hypothesis": "原因仮説 (1-3 文)", "recommendations": [{"title": "...", "rationale": "...", "confidence": 0.0-1.0}]}""",
            )
        }

    private fun parseResponse(raw: String): LlmAnalysis {
        val parsed =
            runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw LlmCallException("Gemini response is not JSON: ${raw.take(200)}") }
        val hypothesis =
            parsed["hypothesis"]?.jsonPrimitive?.content
                ?: throw LlmCallException("missing hypothesis")
        val recs =
            parsed["recommendations"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                Recommendation(
                    title = o["title"]?.jsonPrimitive?.content ?: "(no title)",
                    rationale = o["rationale"]?.jsonPrimitive?.content ?: "",
                    confidence = o["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                )
            }
        return LlmAnalysis(hypothesis, recs, model = model)
    }

    private companion object {
        val SYSTEM_PROMPT =
            """
            あなたはオンコールエンジニアの隣に座る SRE アシスタントです。
            インシデント情報を受け取り、原因仮説と推奨アクションを構造化 JSON で返します。
            推奨は 2-4 件、confidence は 0.0-1.0 の小数で根拠と共に。
            推測は明示し、断定しません。
            """.trimIndent()
    }
}
