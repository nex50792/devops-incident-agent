package com.example.incident.web

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.assertj.MockMvcTester
import kotlin.test.Test
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["incident.mcp.command="])
class IncidentControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `POST incidents で diagnosis JSON を返す`() {
        val tester = MockMvcTester.create(mockMvc)
        val request =
            """
            {"title":"API 5xx 急増","description":"Login で 5xx 多発","severity":"SEV1","service":"auth-api"}
            """.trimIndent()

        val result =
            tester
                .post()
                .uri("/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange()

        result.assertThat().hasStatusOk()
        val response: String = result.assertThat().bodyText().actual()
        assertTrue(response.contains("\"incidentId\""))
        assertTrue(response.contains("\"hypothesis\""))
        assertTrue(response.contains("\"recommendations\""))
        assertTrue(response.contains("エスカレーション"), "SEV1 should include escalation rec")
        assertTrue(response.contains("\"mcpContextFiles\":[]"))
        assertTrue(response.contains("stub-deterministic-v1"))
    }

    @Test
    fun `GET incidents id で保存済み diagnosis を取得`() {
        val tester = MockMvcTester.create(mockMvc)
        val request =
            """
            {"title":"GET test","description":"single fetch","severity":"SEV3","service":"web"}
            """.trimIndent()
        val createResult =
            tester
                .post()
                .uri("/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange()
        val body: String = createResult.assertThat().bodyText().actual()
        val id =
            Regex("\"incidentId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: error("incidentId not found in response")

        val getResult = tester.get().uri("/incidents/$id").exchange()
        getResult.assertThat().hasStatusOk()
        val getBody: String = getResult.assertThat().bodyText().actual()
        assertTrue(getBody.contains("\"hypothesis\""))
        assertTrue(getBody.contains(id))
    }

    @Test
    fun `GET incidents id 未存在は 404`() {
        val tester = MockMvcTester.create(mockMvc)
        val result = tester.get().uri("/incidents/00000000-0000-0000-0000-000000000000").exchange()
        result.assertThat().hasStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    }

    @Test
    fun `GET incidents summary で集計を返す`() {
        val tester = MockMvcTester.create(mockMvc)
        // 何件か作成
        repeat(3) { idx ->
            tester
                .post()
                .uri("/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"summary $idx","description":"d","severity":"SEV1","service":"auth"}""")
                .exchange()
        }
        val result = tester.get().uri("/incidents/summary").exchange()
        result.assertThat().hasStatusOk()
        val body: String = result.assertThat().bodyText().actual()
        assertTrue(body.contains("\"total\""))
        assertTrue(body.contains("\"bySeverity\""))
        assertTrue(body.contains("SEV1"))
        assertTrue(body.contains("auth"))
    }

    @Test
    fun `必須項目欠落は 400`() {
        val tester = MockMvcTester.create(mockMvc)
        val body = """{"title":"","description":"x","severity":"SEV2","service":"x"}"""

        val result =
            tester
                .post()
                .uri("/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()

        result.assertThat().hasStatus4xxClientError()
    }
}
