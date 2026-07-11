package com.example.incident.service

import com.google.genai.errors.ClientException
import com.google.genai.errors.ServerException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiErrorWrappingTest {
    private val zeroJitter: () -> Double = { 0.5 }

    @Test
    fun `ServerException は LlmCallException に変換され status code を含む`() =
        runTest {
            val e =
                assertThrows<LlmCallException> {
                    wrapGeminiCall { throw ServerException(503, "UNAVAILABLE", "high demand") }
                }
            assertTrue(e.message!!.contains("503"))
            assertTrue(e.cause is ServerException)
        }

    @Test
    fun `変換後の 503 は RetryPolicy で retry され復帰できる`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("gemini.test") {
                    wrapGeminiCall {
                        calls++
                        if (calls < 3) throw ServerException(503, "UNAVAILABLE", "high demand")
                        "ok"
                    }
                }
            assertEquals("ok", result)
            assertEquals(3, calls)
        }

    @Test
    fun `4xx の ClientException は retry されず 1 回で失敗する`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            assertThrows<LlmCallException> {
                policy.execute("gemini.test") {
                    wrapGeminiCall {
                        calls++
                        throw ClientException(400, "INVALID_ARGUMENT", "bad request")
                    }
                }
            }
            assertEquals(1, calls)
        }

    @Test
    fun `429 の ClientException は retry される`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("gemini.test") {
                    wrapGeminiCall {
                        calls++
                        if (calls < 2) throw ClientException(429, "RESOURCE_EXHAUSTED", "rate limited")
                        "ok"
                    }
                }
            assertEquals("ok", result)
            assertEquals(2, calls)
        }

    @Test
    fun `MALFORMED_FUNCTION_CALL は一時的なモデル出力エラーとして retry される`() =
        runTest {
            val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, random = zeroJitter)
            var calls = 0
            val result =
                policy.execute("gemini.test") {
                    wrapGeminiCall {
                        calls++
                        if (calls < 2) {
                            throw IllegalArgumentException(
                                "The response finished unexpectedly with reason MALFORMED_FUNCTION_CALL.",
                            )
                        }
                        "ok"
                    }
                }
            assertEquals("ok", result)
            assertEquals(2, calls)
        }

    @Test
    fun `MALFORMED 以外の IllegalArgumentException は変換されず素通しする`() =
        runTest {
            val e =
                assertThrows<IllegalArgumentException> {
                    wrapGeminiCall { throw IllegalArgumentException("some programming error") }
                }
            assertEquals("some programming error", e.message)
        }
}
