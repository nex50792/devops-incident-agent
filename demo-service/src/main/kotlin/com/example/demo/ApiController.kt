package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 仮想 SaaS の 3 サービスを模した endpoint。
 * それぞれ ScenarioExecutor で chaos 状態を確認して障害を発生させる。
 */
@RestController
@RequestMapping("/api")
class ApiController(
    private val executor: ScenarioExecutor,
) {
    private val log = LoggerFactory.getLogger(ApiController::class.java)

    @PostMapping("/auth/login")
    fun login(
        @RequestBody body: Map<String, Any>,
    ): Map<String, Any> {
        executor.executeFor("auth-api")
        val userId = body["user_id"] ?: "u-${UUID.randomUUID()}"
        log.info("auth-api login success user_id={}", userId)
        return mapOf("token" to "tk-${UUID.randomUUID()}", "user_id" to userId)
    }

    @PostMapping("/orders")
    fun createOrder(
        @RequestBody body: Map<String, Any>,
    ): Map<String, Any> {
        executor.executeFor("orders-api")
        val orderId = "ord-${UUID.randomUUID()}"
        log.info("orders-api created order_id={}", orderId)
        return mapOf("order_id" to orderId, "status" to "created")
    }

    @PostMapping("/checkout")
    fun checkout(
        @RequestBody body: Map<String, Any>,
    ): Map<String, Any> {
        executor.executeFor("checkout-api")
        val paymentId = "pay-${UUID.randomUUID()}"
        log.info("checkout-api paid payment_id={}", paymentId)
        return mapOf("payment_id" to paymentId, "status" to "succeeded")
    }
}
