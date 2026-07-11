package com.example.incident.web

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slack の Request Verification (X-Slack-Signature) を検証する。
 *
 * 仕様: https://api.slack.com/authentication/verifying-requests-from-slack
 * - sigBase = "v0:" + timestamp + ":" + body
 * - HMAC-SHA256(signingSecret, sigBase) を hex 化、"v0=" prefix
 * - timestamp が現在時刻から ±5 分以内 (replay 防止)
 *
 * Why HMAC を自前: Slack Bolt SDK 全体を入れずに 50 行程度で済むため。
 *                署名検証ロジックは仕様が安定していて自前実装で問題ない。
 */
class SlackSignatureVerifier(
    private val signingSecret: String,
    private val maxClockSkewSeconds: Long = 300, // 5 分
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun verify(
        timestamp: String,
        signature: String,
        body: String,
    ): Result {
        val ts = timestamp.toLongOrNull() ?: return Result.InvalidTimestamp
        val now = nowEpochSeconds()
        if (kotlin.math.abs(now - ts) > maxClockSkewSeconds) return Result.Stale
        val expected = "v0=" + sign("v0:$ts:$body")
        return if (constantTimeEquals(expected, signature)) Result.Ok else Result.SignatureMismatch
    }

    private fun sign(input: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(input.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    sealed class Result {
        data object Ok : Result()

        data object InvalidTimestamp : Result()

        data object Stale : Result()

        data object SignatureMismatch : Result()
    }
}
