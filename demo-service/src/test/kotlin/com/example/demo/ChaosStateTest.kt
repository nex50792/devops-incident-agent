package com.example.demo

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChaosStateTest {
    @Test
    fun `unset service returns OFF`() {
        val s = ChaosState()
        assertEquals(ChaosState.Scenario.OFF, s.get("unknown"))
    }

    @Test
    fun `set and get`() {
        val s = ChaosState()
        s.set("auth-api", ChaosState.Scenario.DEADLOCK)
        assertEquals(ChaosState.Scenario.DEADLOCK, s.get("auth-api"))
    }

    @Test
    fun `clearAll resets`() {
        val s = ChaosState()
        s.set("a", ChaosState.Scenario.DB_POOL_TIMEOUT)
        s.set("b", ChaosState.Scenario.EXTERNAL_500)
        s.clearAll()
        assertEquals(ChaosState.Scenario.OFF, s.get("a"))
        assertEquals(ChaosState.Scenario.OFF, s.get("b"))
    }

    @Test
    fun `snapshot returns current state`() {
        val s = ChaosState()
        s.set("x", ChaosState.Scenario.SLOW_QUERY)
        val snap = s.snapshot()
        assertEquals(ChaosState.Scenario.SLOW_QUERY, snap["x"])
    }
}
