package com.example.incident.web

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.assertj.MockMvcTester
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "incident.mcp.command=",
        "incident.slack.signing-secret=test-signing-secret-123456",
    ],
)
class SlackControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val secret = "test-signing-secret-123456"

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
    fun `署名なしは 401`() {
        val tester = MockMvcTester.create(mockMvc)
        val result =
            tester
                .post()
                .uri("/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("text=SEV1+auth+5xx")
                .exchange()
        result.assertThat().hasStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `誤った署名は 401`() {
        val tester = MockMvcTester.create(mockMvc)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val result =
            tester
                .post()
                .uri("/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Signature", "v0=ffffffff")
                .header("X-Slack-Request-Timestamp", ts)
                .content("text=SEV1+auth+5xx")
                .exchange()
        result.assertThat().hasStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `正しい署名 + 有効コマンドで ack を返す`() {
        val tester = MockMvcTester.create(mockMvc)
        val ts = System.currentTimeMillis() / 1000
        val body = "command=%2Fincident&text=SEV1+auth-api+5xx+%E6%80%A5%E5%A2%97"
        val sig = sign(ts, body)
        val result =
            tester
                .post()
                .uri("/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", ts.toString())
                .content(body)
                .exchange()
        result.assertThat().hasStatusOk()
        val text: String = result.assertThat().bodyText().actual()
        assertTrue(text.contains("解析中"), "ack should mention analyzing")
        assertTrue(text.contains("incident_id="), "ack should include incident id")
    }

    @Test
    fun `正しい署名 + 無効コマンドは usage を返す`() {
        val tester = MockMvcTester.create(mockMvc)
        val ts = System.currentTimeMillis() / 1000
        val body = "text=invalid"
        val sig = sign(ts, body)
        val result =
            tester
                .post()
                .uri("/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", ts.toString())
                .content(body)
                .exchange()
        result.assertThat().hasStatusOk()
        val text: String = result.assertThat().bodyText().actual()
        assertTrue(text.contains("使い方"), "should return usage on bad text")
    }
}
