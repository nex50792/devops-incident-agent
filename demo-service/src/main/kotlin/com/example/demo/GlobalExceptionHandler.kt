package com.example.demo

import net.logstash.logback.marker.Markers.appendEntries
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * SimulatedException を捕捉して構造化ログを吐く。
 * ログには service / error_signature / trace_id 相当の情報を JSON key として付与し、
 * BigQuery で SQL 分析しやすくする。
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(SimulatedException::class)
    fun handleSimulated(e: SimulatedException): ResponseEntity<Map<String, Any>> {
        log.error(
            appendEntries(
                mapOf(
                    "service" to e.service,
                    "error_signature" to e.signature,
                ),
            ),
            "simulated failure: {}",
            e.message,
        )
        val status =
            when (e) {
                is SimulatedException.External500 -> HttpStatus.BAD_GATEWAY
                is SimulatedException.DbPoolTimeout, is SimulatedException.Deadlock -> HttpStatus.INTERNAL_SERVER_ERROR
            }
        return ResponseEntity.status(status).body(
            mapOf(
                "error" to e.signature,
                "message" to (e.message ?: ""),
            ),
        )
    }
}
