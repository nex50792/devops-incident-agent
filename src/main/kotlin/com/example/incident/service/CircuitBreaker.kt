package com.example.incident.service

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 軽量 Circuit Breaker。resilience4j 全体を入れずに 60 行で書ける最小実装。
 *
 * 状態:
 * - CLOSED: 通常通過
 * - OPEN: 直近 N 回連続失敗 → 全リクエストを fail-fast (即時 CircuitOpenException)
 * - HALF_OPEN: openTimeout 経過後、1 リクエストだけ通して OK なら CLOSED 復帰
 *
 * Why 自前: incident-agent の用途では Gemini 単一 backend の障害遮断が目的、
 * 多数 backend / メトリクス連携が要らない。Phase 2 で resilience4j を検討する場合は
 * この interface を維持して swap 可能。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openTimeout: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(CircuitBreaker::class.java)
    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicReference<Instant?>(null)

    enum class State { CLOSED, OPEN, HALF_OPEN }

    suspend fun <T> execute(
        opName: String,
        block: suspend () -> T,
    ): T {
        when (state.get()) {
            State.OPEN -> {
                val opened = openedAt.get()
                if (opened != null && Duration.between(opened, now()).compareTo(openTimeout) >= 0) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        log.info("circuit_breaker.{}: OPEN -> HALF_OPEN", opName)
                    }
                } else {
                    throw CircuitOpenException("$opName circuit OPEN, fail fast")
                }
            }
            State.HALF_OPEN -> {} // probe を通す
            State.CLOSED -> {}
        }

        try {
            val result = block()
            onSuccess(opName)
            return result
        } catch (e: Throwable) {
            onFailure(opName)
            throw e
        }
    }

    private fun onSuccess(opName: String) {
        consecutiveFailures.set(0)
        if (state.get() == State.HALF_OPEN &&
            state.compareAndSet(State.HALF_OPEN, State.CLOSED)
        ) {
            openedAt.set(null)
            log.info("circuit_breaker.{}: HALF_OPEN -> CLOSED", opName)
        }
    }

    private fun onFailure(opName: String) {
        val failures = consecutiveFailures.incrementAndGet()
        when (state.get()) {
            State.HALF_OPEN -> {
                if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    openedAt.set(now())
                    log.warn("circuit_breaker.{}: HALF_OPEN -> OPEN (probe failed)", opName)
                }
            }
            State.CLOSED -> {
                if (failures >= failureThreshold &&
                    state.compareAndSet(State.CLOSED, State.OPEN)
                ) {
                    openedAt.set(now())
                    log.warn("circuit_breaker.{}: CLOSED -> OPEN (failures={})", opName, failures)
                }
            }
            State.OPEN -> {} // already open
        }
    }

    fun currentState(): State = state.get()
}

class CircuitOpenException(
    message: String,
) : RuntimeException(message)
