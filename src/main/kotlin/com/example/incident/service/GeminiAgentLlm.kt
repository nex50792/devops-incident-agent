package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation
import com.example.incident.tools.ToolRegistry
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Gemini function calling を使った ReAct full エージェント。
 *
 * 1 turn ごとに generateContent を呼び、response.functionCalls() が空でなければ
 * CallTool を返す。空(最終回答)なら response.text() を JSON パースして Final を返す。
 *
 * Automatic Function Calling (AFC) は使わず、tool 実行は呼出元 (ReActAgentService) が
 * 管理する。tool declaration は ToolRegistry から動的に取得するので、
 * 新規 tool の追加時にこのクラスを変更する必要はない。
 */
class GeminiAgentLlm(
    private val apiKey: String,
    private val registry: ToolRegistry,
    private val model: String = "gemini-2.5-flash",
    private val clientOverride: Client? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
) : AgentLlm {
    private val log = LoggerFactory.getLogger(GeminiAgentLlm::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client: Client by lazy {
        clientOverride ?: Client.builder().apiKey(apiKey).build()
    }

    override suspend fun decide(
        incident: Incident,
        history: List<AgentTurn>,
    ): AgentDecision =
        circuitBreaker.execute("gemini.decide") {
            retryPolicy.execute("gemini.decide") {
                wrapGeminiCall {
                    withContext(Dispatchers.IO) {
                        val response =
                            client.models.generateContent(
                                model,
                                buildContents(incident, history),
                                buildConfig(),
                            )
                        parseDecision(response.functionCalls()?.toList() ?: emptyList(), response.text())
                    }
                }
            }
        }

    private fun buildConfig(): GenerateContentConfig =
        GenerateContentConfig
            .builder()
            .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_PROMPT)))
            .tools(listOf(buildTool()))
            .build()

    private fun buildTool(): Tool =
        Tool
            .builder()
            .functionDeclarations(
                registry.all().map { tool ->
                    FunctionDeclaration
                        .builder()
                        .name(tool.name)
                        .description(tool.description)
                        .parameters(tool.parameters)
                        .build()
                },
            ).build()

    private fun buildContents(
        incident: Incident,
        history: List<AgentTurn>,
    ): List<Content> {
        val list = mutableListOf<Content>()
        list.add(
            Content
                .builder()
                .role("user")
                .parts(listOf(Part.fromText(initialUserPrompt(incident))))
                .build(),
        )
        for (turn in history) {
            when (turn.role) {
                AgentTurn.Role.ASSISTANT -> {
                    val args =
                        runCatching { parseArgs(turn.toolInput ?: "{}") }
                            .getOrDefault(emptyMap())
                    list.add(
                        Content
                            .builder()
                            .role("model")
                            .parts(listOf(Part.fromFunctionCall(turn.toolName ?: "unknown", args)))
                            .build(),
                    )
                }
                AgentTurn.Role.TOOL_RESULT -> {
                    list.add(
                        Content
                            .builder()
                            .role("user")
                            .parts(
                                listOf(
                                    Part.fromFunctionResponse(
                                        turn.toolName ?: "unknown",
                                        mapOf("content" to (turn.toolResult ?: "")),
                                    ),
                                ),
                            ).build(),
                    )
                }
                AgentTurn.Role.USER -> {
                    list.add(
                        Content
                            .builder()
                            .role("user")
                            .parts(listOf(Part.fromText(turn.text ?: "")))
                            .build(),
                    )
                }
            }
        }
        return list
    }

    private fun parseArgs(rawJson: String): Map<String, Any> {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        return obj.mapValues { (_, v) ->
            when {
                v.jsonPrimitive.isString -> v.jsonPrimitive.content
                else -> v.jsonPrimitive.content.toIntOrNull() ?: v.jsonPrimitive.content
            }
        }
    }

    private fun parseDecision(
        functionCalls: List<com.google.genai.types.FunctionCall>,
        text: String?,
    ): AgentDecision {
        if (functionCalls.isNotEmpty()) {
            val fc = functionCalls.first()
            val name = fc.name().orElse("unknown")
            val id = fc.id().orElse(name)
            val args = fc.args().orElse(emptyMap())
            val argsJson =
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject
                        .serializer(),
                    toJsonObject(args),
                )
            return AgentDecision.CallTool(toolUseId = id, toolName = name, toolInput = argsJson)
        }
        val finalText = text ?: throw LlmCallException("Gemini response has neither functionCalls nor text")
        val cleaned = stripCodeFence(finalText)
        val parsed =
            runCatching { json.parseToJsonElement(cleaned).jsonObject }
                .getOrElse { throw LlmCallException("final response is not JSON: ${finalText.take(200)}") }
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
        return AgentDecision.Final(LlmAnalysis(hypothesis, recs, model))
    }

    private fun toJsonObject(map: Map<String, Any>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json
            .JsonObject(
                map.mapValues { (_, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Int -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Long -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Double -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                    }
                },
            )

    private fun initialUserPrompt(incident: Incident): String =
        buildString {
            append("以下のインシデントを解析してください。利用可能な tool を必要に応じて呼び出し、")
            append("十分な情報が集まったら最終回答を JSON で返してください。\n\n")
            append("- タイトル: ${incident.title}\n")
            append("- 詳細: ${incident.description}\n")
            append("- Severity: ${incident.severity}\n")
            append("- Service: ${incident.service}\n\n")
            append("最終回答 (tool を呼ばない turn) では以下の JSON 形式で返答してください:\n")
            append("""{"hypothesis":"原因仮説","recommendations":[{"title":"...","rationale":"...","confidence":0.0-1.0}]}""")
        }

    private fun stripCodeFence(s: String): String {
        val trimmed = s.trim()
        if (trimmed.startsWith("```")) {
            return trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        return trimmed
    }

    private companion object {
        val SYSTEM_PROMPT =
            """
            あなたはオンコールエンジニアの隣に座る SRE アシスタントです。
            利用可能な tool を必要に応じて呼び、ログ / 直近変更 / 過去 postmortem 等の情報を集めた上で、
            原因仮説と推奨アクションを JSON で返してください。
            tool 呼出は最大 3-4 回程度に抑え、十分な情報が集まったら速やかに最終回答を出してください。
            """.trimIndent()
    }
}
