package com.example.incident.tools

import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * GitHub Search API で直近の PR / commit を検索する。
 * agent は「症状が始まる直前の deploy に該当する変更あるか」を確認するために使う。
 */
class SearchRecentChangesTool(
    private val githubToken: String?,
    private val defaultRepo: String? = null,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Tool {
    override val name: String = "search_recent_changes"
    override val description: String =
        "GitHub Search API で直近 PR / commit を検索。repo と since (RFC3339) を渡すと 24-48h の変更を返す。"
    override val parameters: Schema =
        Schema
            .builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "repo" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("owner/name 形式")
                            .build(),
                    "since" to
                        Schema
                            .builder()
                            .type(Type.Known.STRING)
                            .description("いつ以降を検索するか (RFC3339)")
                            .build(),
                ),
            ).required(listOf("since"))
            .build()

    private val log = LoggerFactory.getLogger(SearchRecentChangesTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(inputJson: String): String =
        withContext(Dispatchers.IO) {
            val params = json.parseToJsonElement(inputJson).jsonObject
            val repo =
                params["repo"]?.jsonPrimitive?.content
                    ?: defaultRepo
                    ?: return@withContext errorResponse("repo (owner/name) required")
            val since =
                params["since"]?.jsonPrimitive?.content
                    ?: return@withContext errorResponse("since (RFC3339) required")

            val query = URLEncoder.encode("repo:$repo is:pr is:merged merged:>=$since", StandardCharsets.UTF_8)
            val uri = URI.create("https://api.github.com/search/issues?q=$query&sort=updated&order=desc&per_page=20")
            val requestBuilder =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("accept", "application/vnd.github+json")
                    .header("x-github-api-version", "2022-11-28")
                    .GET()
            if (!githubToken.isNullOrBlank()) {
                requestBuilder.header("authorization", "Bearer $githubToken")
            }
            val res = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) {
                return@withContext errorResponse("GitHub API ${res.statusCode()}: ${res.body().take(200)}")
            }

            val body = json.parseToJsonElement(res.body()).jsonObject
            val items = body["items"]?.jsonArray.orEmpty()
            buildJsonObject {
                put("repo", repo)
                put("since", since)
                put("total_count", body["total_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                put(
                    "recent_prs",
                    buildJsonArray {
                        items.take(10).forEach { item ->
                            val o = item.jsonObject
                            add(
                                buildJsonObject {
                                    put("number", o["number"]?.jsonPrimitive?.content ?: "")
                                    put("title", o["title"]?.jsonPrimitive?.content ?: "")
                                    put("merged_at", o["closed_at"]?.jsonPrimitive?.content ?: "")
                                    put("html_url", o["html_url"]?.jsonPrimitive?.content ?: "")
                                },
                            )
                        }
                    },
                )
            }.toString()
        }

    private fun errorResponse(msg: String): String =
        buildJsonObject {
            put("error", msg)
        }.toString()
}
