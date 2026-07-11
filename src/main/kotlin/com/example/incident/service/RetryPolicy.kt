package com.example.incident.service

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.http.HttpTimeoutException

/**
 * 軽量 retry policy。resilience4j を導入する前の最小実装。
 *
 * - 5xx / IOException / Timeout のみ retry
 * - 4xx (client error) は retry しない (バグ / 設定ミスを増幅させない)
 * - exponential backoff (initialDelay × 2^attempt) + jitter (±20%)
 *
 * Why 自前: resilience4j 全体は依存が大きい (Vavr / Reactor 等)。
 * 単一 HTTP 呼出に retry をかけたいだけなら 30 行で十分。
 * Phase 2 で circuit breaker / bulkhead が要れば resilience4j を導入。
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 250,
    val maxDelayMs: Long = 5000,
    private val random: () -> Double = Math::random,
) {
    private val log = LoggerFactory.getLogger(RetryPolicy::class.java)

    suspend fun <T> execute(
        opName: String,
        block: suspend () -> T,
    ): T {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: LlmCallException) {
                if (!retryableLlm(e)) throw e
                lastError = e
            } catch (e: HttpTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) {
                val delayMs = backoffMs(attempt)
                log.warn("retry.{}.attempt={} delay={}ms err={}", opName, attempt, delayMs, lastError?.message)
                delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("retry exhausted with no error")
    }

    private fun retryableLlm(e: LlmCallException): Boolean {
        if (e.retryable) return true
        // メッセージ先頭の "LLM API NNN:" 形式から status を抽出
        val match =
            Regex("(\\d{3})")
                .find(e.message.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return match in 500..599 || match == 429
    }

    private fun backoffMs(attempt: Int): Long {
        val base = (initialDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
        val jitter = base * (random() * 0.4 - 0.2) // ±20%
        return (base + jitter).toLong().coerceAtLeast(0L)
    }
}
