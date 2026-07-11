package com.example.incident.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals

class RetryPolicyTest {
    private val zeroJitter: () -> Double = { 0.5 } // ±0% (中央値)

    @Test
    fun `5xx LlmCallException は retry される`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("test") {
                    calls++
                    if (calls < 3) throw LlmCallException("Gemini API 503: down")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(3, calls)
        }

    @Test
    fun `429 (Too Many Requests) も retry される`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("test") {
                    calls++
                    if (calls < 2) throw LlmCallException("Gemini API 429: rate limited")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, calls)
        }

    @Test
    fun `4xx (401 等) は retry されず即座に投げる`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            assertThrows<LlmCallException> {
                policy.execute("test") {
                    calls++
                    throw LlmCallException("Gemini API 401: unauthorized")
                }
            }
            assertEquals(1, calls, "401 should not retry")
        }

    @Test
    fun `IOException は retry される`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("test") {
                    calls++
                    if (calls < 2) throw IOException("connection reset")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, calls)
        }

    @Test
    fun `maxAttempts 超過時は最後の例外を投げる`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val ex =
                assertThrows<IOException> {
                    policy.execute("test") {
                        calls++
                        throw IOException("always fail")
                    }
                }
            assertEquals(2, calls)
            assertEquals("always fail", ex.message)
        }

    @Test
    fun `HttpTimeoutException も retry される (boundary - 既存 IOException パスとは別経路)`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("test") {
                    calls++
                    if (calls < 3) throw java.net.http.HttpTimeoutException("read timeout 30s")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(3, calls)
        }

    @Test
    fun `LlmCallException で status code 含まないメッセージは retry されない (regex 失敗 = 4xx 扱い)`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            assertThrows<LlmCallException> {
                policy.execute("test") {
                    calls++
                    throw LlmCallException("malformed JSON response (no status code)")
                }
            }
            assertEquals(1, calls, "no extractable status → not retryable")
        }
}
