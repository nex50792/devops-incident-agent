package com.example.incident.web

import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

class SlackSignatureVerifierTest {
    private val secret = "test-signing-secret-12345"
    private val now = 1700000000L
    private val verifier = SlackSignatureVerifier(secret, nowEpochSeconds = { now })

    private fun sign(
        ts: Long,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val raw = mac.doFinal("v0:$ts:$body".toByteArray())
        return "v0=" + raw.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `正しい署名は Ok`() {
        val body = "command=/incident&text=SEV1+auth+5xx"
        val sig = sign(now, body)
        assertEquals(SlackSignatureVerifier.Result.Ok, verifier.verify(now.toString(), sig, body))
    }

    @Test
    fun `誤った署名は SignatureMismatch`() {
        val body = "command=/incident"
        assertEquals(
            SlackSignatureVerifier.Result.SignatureMismatch,
            verifier.verify(now.toString(), "v0=ffffffffffff", body),
        )
    }

    @Test
    fun `5 分以上前は Stale`() {
        val staleTs = now - 600
        val body = "x=1"
        val sig = sign(staleTs, body)
        assertEquals(SlackSignatureVerifier.Result.Stale, verifier.verify(staleTs.toString(), sig, body))
    }

    @Test
    fun `非数値 timestamp は InvalidTimestamp`() {
        assertEquals(
            SlackSignatureVerifier.Result.InvalidTimestamp,
            verifier.verify("not-a-number", "v0=00", ""),
        )
    }
}
