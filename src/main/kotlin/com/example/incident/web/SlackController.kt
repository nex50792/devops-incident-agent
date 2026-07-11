package com.example.incident.web

import com.example.incident.domain.Incident
import com.example.incident.domain.Severity
import com.example.incident.service.IncidentAnalysisService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Slack スラッシュコマンド (例: /incident SEV1 auth-api 5xx 急増) 受信エンドポイント。
 *
 * フロー:
 * 1. HMAC 署名検証 (X-Slack-Signature, X-Slack-Request-Timestamp)
 * 2. body をフォームパース
 * 3. command + text から Severity / Service / Title を抽出
 * 4. 即時 ack を返す ("解析中... [<id>]")
 * 5. バックグラウンドで IncidentAnalysisService.analyze を実行、response_url に POST
 *
 * incident.slack.signing-secret 未設定の場合は 503 を返す (機能 OFF)。
 */
@RestController
@RequestMapping("/slack")
class SlackController(
    private val service: IncidentAnalysisService,
    @Value("\${incident.slack.signing-secret:#{null}}") private val signingSecret: String?,
) {
    private val log = LoggerFactory.getLogger(SlackController::class.java)
    private val verifier: SlackSignatureVerifier? =
        signingSecret?.takeIf { it.isNotBlank() }?.let { SlackSignatureVerifier(it) }
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient.newHttpClient()

    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun handle(
        @RequestHeader("X-Slack-Signature", required = false) signature: String?,
        @RequestHeader("X-Slack-Request-Timestamp", required = false) timestamp: String?,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val v =
            verifier
                ?: return ResponseEntity.status(503).body("slack integration not configured")
        if (signature == null || timestamp == null) {
            return ResponseEntity.status(401).body("missing signature headers")
        }
        when (v.verify(timestamp, signature, body)) {
            SlackSignatureVerifier.Result.Ok -> {}
            SlackSignatureVerifier.Result.Stale -> return ResponseEntity.status(401).body("stale request")
            SlackSignatureVerifier.Result.SignatureMismatch -> return ResponseEntity.status(401).body("bad signature")
            SlackSignatureVerifier.Result.InvalidTimestamp -> return ResponseEntity.status(400).body("bad timestamp")
        }

        val params = parseForm(body)
        val text = params["text"].orEmpty().trim()
        val responseUrl = params["response_url"].orEmpty()

        val parsed =
            parseCommand(text)
                ?: return ack("使い方: /incident <SEV1|SEV2|SEV3|SEV4> <service> <title...>")

        val incident = Incident.create(parsed.title, parsed.title, parsed.severity, parsed.service)
        log.info("slack.received: incident_id={} severity={} service={}", incident.id, parsed.severity, parsed.service)

        if (responseUrl.isNotBlank()) {
            asyncScope.launch {
                try {
                    val diagnosis = service.analyze(incident)
                    postFollowup(responseUrl, formatFollowup(diagnosis))
                } catch (e: Exception) {
                    log.error("slack.followup failed: {}", e.message)
                }
            }
        }
        return ack("解析中... incident_id=${incident.id} (3-10s で結果を返信)")
    }

    private fun ack(text: String): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("""{"response_type":"ephemeral","text":${jsonString(text)}}""")

    private fun jsonString(s: String): String = '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'

    private fun parseForm(body: String): Map<String, String> =
        body
            .split("&")
            .mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx < 0) {
                    null
                } else {
                    URLDecoder.decode(kv.substring(0, idx), Charsets.UTF_8) to
                        URLDecoder.decode(kv.substring(idx + 1), Charsets.UTF_8)
                }
            }.toMap()

    private fun parseCommand(text: String): ParsedCommand? {
        if (text.isBlank()) return null
        val parts = text.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) return null
        val sev = runCatching { Severity.valueOf(parts[0].uppercase()) }.getOrNull() ?: return null
        return ParsedCommand(severity = sev, service = parts[1], title = parts[2])
    }

    private fun formatFollowup(diagnosis: com.example.incident.domain.Diagnosis): String {
        val recs =
            diagnosis.recommendations.joinToString("\n") {
                "• *${it.title}* (${"%.0f%%".format(it.confidence * 100)}): ${it.rationale}"
            }
        val text =
            """
            *Incident:* ${diagnosis.incidentId}
            *Hypothesis:* ${diagnosis.hypothesis}
            *Recommendations:*
            $recs
            _model: ${diagnosis.llmModel}, trace: ${diagnosis.trace.size} step_
            """.trimIndent()
        return """{"response_type":"in_channel","text":${jsonString(text)}}"""
    }

    private fun postFollowup(
        responseUrl: String,
        jsonBody: String,
    ) {
        val req =
            HttpRequest
                .newBuilder(URI.create(responseUrl))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
        httpClient.send(req, HttpResponse.BodyHandlers.discarding())
    }

    private data class ParsedCommand(
        val severity: Severity,
        val service: String,
        val title: String,
    )
}
