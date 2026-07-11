package com.example.demo

/**
 * 障害シナリオを表す例外群。
 * demo-service が意図的に throw して構造化ログにエラーを吐かせる。
 */
sealed class SimulatedException(
    val service: String,
    val signature: String,
    message: String,
) : RuntimeException(message) {
    class DbPoolTimeout(
        service: String,
    ) : SimulatedException(service, "ConnectionTimeoutException:pool_exhausted", "connection pool timeout after 5s")

    class External500(
        service: String,
    ) : SimulatedException(service, "ExternalApiException:HTTP_500", "downstream API returned 500")

    class Deadlock(
        service: String,
    ) : SimulatedException(service, "DeadlockDetectedException", "database deadlock detected on write")
}
