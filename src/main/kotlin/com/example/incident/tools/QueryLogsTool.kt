package com.example.incident.tools

import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.FieldValueList
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.QueryParameterValue
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

/**
 * demo-service が Cloud Run stdout に吐いた JSON ログを Cloud Logging 経由で
 * BigQuery sink した table (`{project}.logs.run_googleapis_com_stdout`) にクエリを飛ばす。
 *
 * 直近 5-60 分の error_signature 集計 + first_seen / last_seen で
 * 「どの signature が今急増しているか」を返す。
 */
class QueryLogsTool(
    private val bigQuery: BigQuery,
    private val projectId: String,
    private val dataset: String = "logs",
    private val table: String = "run_googleapis_com_stdout",
) : Tool {
    override val name: String = "query_logs"
    override val description: String =
        "アプリケーションログを構造化クエリで検索。service / 時刻範囲 / severity で絞って error_signature を集計する。"
    override val parameters: Schema =
        Schema
            .builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "service" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("対象サービス名 (auth-api 等)")
                            .build(),
                    "start_time" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("検索開始 RFC3339")
                            .build(),
                    "end_time" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("検索終了 RFC3339")
                            .build(),
                    "level" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("severity (ERROR / WARN / INFO)、省略時 ERROR")
                            .build(),
                ),
            ).required(listOf("service", "start_time", "end_time"))
            .build()

    private val log = LoggerFactory.getLogger(QueryLogsTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(inputJson: String): String =
        withContext(Dispatchers.IO) {
            val params = json.parseToJsonElement(inputJson).jsonObject
            val service = params["service"]?.jsonPrimitive?.content ?: return@withContext errorResponse("service required")
            val startTime =
                params["start_time"]?.jsonPrimitive?.content
                    ?: return@withContext errorResponse("start_time (RFC3339) required")
            val endTime =
                params["end_time"]?.jsonPrimitive?.content
                    ?: return@withContext errorResponse("end_time (RFC3339) required")
            val level = params["level"]?.jsonPrimitive?.content ?: "ERROR"

            val sql =
                """
                SELECT
                  JSON_VALUE(jsonPayload, '${'$'}.error_signature') AS signature,
                  COUNT(*) AS count,
                  MIN(timestamp) AS first_seen,
                  MAX(timestamp) AS last_seen
                FROM `$projectId.$dataset.$table`
                WHERE
                  timestamp BETWEEN @start_time AND @end_time
                  AND JSON_VALUE(jsonPayload, '${'$'}.service') = @service
                  AND severity = @level
                GROUP BY signature
                ORDER BY count DESC
                LIMIT 20
                """.trimIndent()

            val config =
                QueryJobConfiguration
                    .newBuilder(sql)
                    .addNamedParameter("service", QueryParameterValue.string(service))
                    .addNamedParameter("start_time", QueryParameterValue.timestamp(startTime.toBqTimestamp()))
                    .addNamedParameter("end_time", QueryParameterValue.timestamp(endTime.toBqTimestamp()))
                    .addNamedParameter("level", QueryParameterValue.string(level))
                    .build()

            val result = bigQuery.query(config)
            val rows = result.iterateAll().map { formatRow(it) }
            buildJsonObject {
                put("service", service)
                put("start_time", startTime)
                put("end_time", endTime)
                put("level", level)
                put("row_count", rows.size)
                put(
                    "top_signatures",
                    buildJsonArray {
                        rows.forEach { add(it) }
                    },
                )
            }.toString()
        }

    private fun formatRow(row: FieldValueList) =
        buildJsonObject {
            put("signature", row.get("signature").stringValue.orEmpty())
            put("count", row.get("count").longValue)
            put(
                "first_seen",
                row
                    .get("first_seen")
                    .timestampInstant
                    ?.toString()
                    .orEmpty(),
            )
            put(
                "last_seen",
                row
                    .get("last_seen")
                    .timestampInstant
                    ?.toString()
                    .orEmpty(),
            )
        }

    private fun errorResponse(msg: String): String =
        buildJsonObject {
            put("error", msg)
        }.toString()

    /** RFC3339 (2026-07-08T22:00:00Z) → BQ TIMESTAMP 形式 (2026-07-08 22:00:00) 相当を送信 */
    private fun String.toBqTimestamp(): Long =
        java.time.Instant
            .parse(this)
            .toEpochMilli() * 1000L
}
