package com.example.demo

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Chaos 状態を切り替える管理エンドポイント。
 * frontend の「障害を起こす」ボタンから叩かれる。
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val chaos: ChaosState,
) {
    data class ChaosRequest(
        val service: String,
        val scenario: String,
    )

    @PostMapping("/chaos")
    fun setChaos(
        @RequestBody req: ChaosRequest,
    ): Map<String, Any> {
        val scenario =
            runCatching { ChaosState.Scenario.valueOf(req.scenario.uppercase()) }
                .getOrElse { ChaosState.Scenario.OFF }
        chaos.set(req.service, scenario)
        return mapOf("service" to req.service, "scenario" to scenario.name)
    }

    @PostMapping("/chaos/clear")
    fun clearAll(): Map<String, Any> {
        chaos.clearAll()
        return mapOf("cleared" to true)
    }

    @GetMapping("/status")
    fun status(): Map<String, Any> = mapOf("chaos" to chaos.snapshot().mapValues { it.value.name })
}
