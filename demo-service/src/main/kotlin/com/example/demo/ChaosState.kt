package com.example.demo

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * サービス名 → 障害シナリオを保持する状態オブジェクト。
 * demo-service が意図的に エラーを吐く時、どのサービスをどのシナリオで倒すかを決める。
 */
@Component
class ChaosState {
    enum class Scenario {
        OFF,
        DB_POOL_TIMEOUT,
        EXTERNAL_500,
        DEADLOCK,
        SLOW_QUERY,
    }

    private val state = ConcurrentHashMap<String, Scenario>()

    fun set(
        service: String,
        scenario: Scenario,
    ) {
        state[service] = scenario
    }

    fun clearAll() {
        state.clear()
    }

    fun get(service: String): Scenario = state[service] ?: Scenario.OFF

    fun snapshot(): Map<String, Scenario> = state.toMap()
}
