package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ScenarioExecutor(
    private val chaos: ChaosState,
) {
    private val log = LoggerFactory.getLogger(ScenarioExecutor::class.java)

    /**
     * 現在の Chaos 状態に基づいて障害を発生させる。
     * SLOW_QUERY のみ throw せずレイテンシを増やす。
     */
    fun executeFor(service: String) {
        when (chaos.get(service)) {
            ChaosState.Scenario.OFF -> Unit
            ChaosState.Scenario.DB_POOL_TIMEOUT -> throw SimulatedException.DbPoolTimeout(service)
            ChaosState.Scenario.EXTERNAL_500 -> throw SimulatedException.External500(service)
            ChaosState.Scenario.DEADLOCK -> throw SimulatedException.Deadlock(service)
            ChaosState.Scenario.SLOW_QUERY -> {
                log.warn("slow query simulated: sleeping 5s")
                Thread.sleep(5_000)
            }
        }
    }
}
