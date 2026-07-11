package com.example.demo

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var chaos: ChaosState

    @Test
    fun `login returns 200 in normal mode`() {
        chaos.set("auth-api", ChaosState.Scenario.OFF)
        mvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"user_id": "u-1"}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `login returns 500 when DB_POOL_TIMEOUT is set`() {
        chaos.set("auth-api", ChaosState.Scenario.DB_POOL_TIMEOUT)
        mvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"user_id": "u-1"}"""),
            ).andExpect(status().isInternalServerError)
        chaos.clearAll()
    }

    @Test
    fun `orders returns 502 when EXTERNAL_500 is set`() {
        chaos.set("orders-api", ChaosState.Scenario.EXTERNAL_500)
        mvc
            .perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}"""),
            ).andExpect(status().isBadGateway)
        chaos.clearAll()
    }

    @Test
    fun `checkout returns 500 when DEADLOCK is set`() {
        chaos.set("checkout-api", ChaosState.Scenario.DEADLOCK)
        mvc
            .perform(
                post("/api/checkout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}"""),
            ).andExpect(status().isInternalServerError)
        chaos.clearAll()
    }
}
