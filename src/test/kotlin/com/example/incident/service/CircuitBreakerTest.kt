package com.example.incident.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class CircuitBreakerTest {
    private var fakeNow = Instant.ofEpochSecond(1_700_000_000)
    private val now: () -> Instant = { fakeNow }

    private fun cb(): CircuitBreaker =
        CircuitBreaker(
            failureThreshold = 3,
            openTimeout = Duration.ofSeconds(10),
            now = now,
        )

    @Test
    fun `初期状態は CLOSED、成功は通る`() =
        runTest {
            val cb = cb()
            val r = cb.execute("op") { "ok" }
            assertEquals("ok", r)
            assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        }

    @Test
    fun `閾値回連続失敗で OPEN へ`() =
        runTest {
            val cb = cb()
            repeat(3) {
                assertThrows<RuntimeException> {
                    cb.execute("op") { throw RuntimeException("fail") }
                }
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
        }

    @Test
    fun `OPEN 中は CircuitOpenException で fail fast`() =
        runTest {
            val cb = cb()
            repeat(3) {
                assertThrows<RuntimeException> {
                    cb.execute("op") { throw RuntimeException("fail") }
                }
            }
            assertThrows<CircuitOpenException> {
                cb.execute("op") { "should not run" }
            }
        }

    @Test
    fun `openTimeout 経過後は HALF_OPEN になり probe を通す`() =
        runTest {
            val cb = cb()
            repeat(3) {
                runCatching { cb.execute("op") { throw RuntimeException("fail") } }
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

            fakeNow = fakeNow.plusSeconds(11)

            // probe 成功で CLOSED 復帰
            val r = cb.execute("op") { "recovered" }
            assertEquals("recovered", r)
            assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        }

    @Test
    fun `HALF_OPEN で probe 失敗すると OPEN に戻る`() =
        runTest {
            val cb = cb()
            repeat(3) {
                runCatching { cb.execute("op") { throw RuntimeException("fail") } }
            }
            fakeNow = fakeNow.plusSeconds(11)

            assertThrows<RuntimeException> {
                cb.execute("op") { throw RuntimeException("still failing") }
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.currentState())
        }

    @Test
    fun `openTimeout 直前 (10s 未満) は OPEN のまま、超えると HALF_OPEN`() =
        runTest {
            val cb = cb()
            repeat(3) {
                runCatching { cb.execute("op") { throw RuntimeException("fail") } }
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.currentState())

            // 9 秒経過 → まだ OPEN
            fakeNow = fakeNow.plusSeconds(9)
            assertThrows<CircuitOpenException> {
                cb.execute("op") { "should still be open" }
            }

            // さらに 2 秒で合計 11 秒 → HALF_OPEN へ遷移可能
            fakeNow = fakeNow.plusSeconds(2)
            val r = cb.execute("op") { "now allowed" }
            assertEquals("now allowed", r)
        }

    @Test
    fun `成功で連続失敗カウンタがリセットされる`() =
        runTest {
            val cb = cb()
            runCatching { cb.execute("op") { throw RuntimeException("fail 1") } }
            runCatching { cb.execute("op") { throw RuntimeException("fail 2") } }
            cb.execute("op") { "ok" } // reset
            runCatching { cb.execute("op") { throw RuntimeException("fail 3") } }
            runCatching { cb.execute("op") { throw RuntimeException("fail 4") } }
            // まだ閾値 (3 連続) に達していない
            assertEquals(CircuitBreaker.State.CLOSED, cb.currentState())
        }
}
