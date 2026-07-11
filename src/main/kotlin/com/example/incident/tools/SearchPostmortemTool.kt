package com.example.incident.tools

import com.google.genai.Client
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 過去 postmortem を pgvector 意味検索する tool。
 *
 * クエリ text を Gemini `gemini-embedding-001` で 1536 次元 embedding に変換し、
 * postmortems テーブルで cosine 類似度 (`<=>`) 上位 K 件を返す。
 *
 * table 定義: postmortems(id text primary key, title text, summary text, embedding vector(1536))
 */
class SearchPostmortemTool(
    private val geminiClient: Client,
    private val jdbc: JdbcTemplate,
    private val embeddingModel: String = "gemini-embedding-001",
    private val outputDimensionality: Int = 1536,
) : Tool {
    override val name: String = "search_postmortem"
    override val description: String =
        "過去の postmortem / runbook を pgvector 意味検索。症状文字列 (query_text) を渡すと類似ケース上位を返す。"
    override val parameters: Schema =
        Schema
            .builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "query_text" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("症状の自然文")
                            .build(),
                    "top_k" to
                        Schema
                            .builder()
                            .type(Type.Known.INTEGER)
                            .description("返却件数 (default 3)")
                            .build(),
                ),
            ).required(listOf("query_text"))
            .build()

    private val log = LoggerFactory.getLogger(SearchPostmortemTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(inputJson: String): String =
        withContext(Dispatchers.IO) {
            val params = json.parseToJsonElement(inputJson).jsonObject
            val queryText =
                params["query_text"]?.jsonPrimitive?.content
                    ?: return@withContext errorResponse("query_text required")
            val topK =
                params["top_k"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toIntOrNull()
                    ?.coerceIn(1, 10) ?: 3

            val embedding = embed(queryText) ?: return@withContext errorResponse("embedding failed")
            val vector = embedding.joinToString(",", prefix = "[", postfix = "]")

            val rows =
                jdbc.queryForList(
                    """
                    SELECT id, title, summary, 1 - (embedding <=> ?::vector) AS similarity
                    FROM postmortems
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """.trimIndent(),
                    vector,
                    vector,
                    topK,
                )

            buildJsonObject {
                put("query_text", queryText)
                put("top_k", topK)
                put("result_count", rows.size)
                put(
                    "matches",
                    buildJsonArray {
                        rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row["id"].toString())
                                    put("title", row["title"].toString())
                                    put("summary", row["summary"].toString())
                                    put("similarity", (row["similarity"] as? Number)?.toDouble() ?: 0.0)
                                },
                            )
                        }
                    },
                )
            }.toString()
        }

    /** Gemini embedding call. 失敗時 null。 */
    fun embed(text: String): List<Float>? {
        val config =
            EmbedContentConfig
                .builder()
                .outputDimensionality(outputDimensionality)
                .taskType("RETRIEVAL_QUERY")
                .build()
        val response = geminiClient.models.embedContent(embeddingModel, text, config)
        return response
            .embeddings()
            .orElse(emptyList())
            .firstOrNull()
            ?.values()
            ?.orElse(null)
    }

    private fun errorResponse(msg: String): String = buildJsonObject { put("error", msg) }.toString()
}
