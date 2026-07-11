package com.example.incident.service

import com.example.incident.domain.Diagnosis
import com.example.incident.domain.Incident
import com.example.incident.domain.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IncidentStoreTest {
    private fun stub(
        title: String,
        sev: Severity,
        svc: String,
    ): Pair<Incident, Diagnosis> {
        val i = Incident.create(title, "$title detail", sev, svc)
        val d =
            Diagnosis(
                incidentId = i.id,
                hypothesis = "hypothesis for $title",
                recommendations = emptyList(),
                mcpContextFiles = emptyList(),
                llmModel = "stub",
                trace = emptyList(),
            )
        return i to d
    }

    @Test
    fun `save と get がラウンドトリップする`() {
        val store = IncidentStore()
        val (i, d) = stub("API 5xx", Severity.SEV1, "auth")
        store.save(i, d)
        val got = store.get(i.id)
        assertNotNull(got)
        assertEquals("API 5xx", got.incident.title)
        assertEquals("hypothesis for API 5xx", got.diagnosis.hypothesis)
    }

    @Test
    fun `service フィルタが効く`() {
        val store = IncidentStore()
        val (i0, d0) = stub("a", Severity.SEV1, "auth")
        val (i1, d1) = stub("auth issue", Severity.SEV1, "auth")
        val (i2, d2) = stub("billing issue", Severity.SEV1, "billing")
        store.save(i0, d0)
        store.save(i1, d1)
        store.save(i2, d2)
        val results = store.search(service = "auth")
        assertEquals(2, results.size) // 「a」と「auth issue」両方 auth
    }

    @Test
    fun `severity フィルタが効く`() {
        val store = IncidentStore()
        val (i1, d1) = stub("a", Severity.SEV1, "x")
        val (i2, d2) = stub("b", Severity.SEV3, "x")
        store.save(i1, d1)
        store.save(i2, d2)
        val results = store.search(severity = Severity.SEV1)
        assertEquals(1, results.size)
        assertEquals("a", results[0].incident.title)
    }

    @Test
    fun `keyword フィルタが title hypothesis description に効く`() {
        val store = IncidentStore()
        val (i, d) = stub("DB pool exhaustion", Severity.SEV2, "x")
        store.save(i, d)
        assertEquals(1, store.search(keyword = "pool").size)
        assertEquals(1, store.search(keyword = "POOL").size) // case-insensitive
        assertEquals(0, store.search(keyword = "kafka").size)
    }

    @Test
    fun `存在しない id は null`() {
        val store = IncidentStore()
        assertNull(store.get(java.util.UUID.randomUUID()))
    }

    @Test
    fun `limit 引数で結果数を絞る (デフォルト 20)`() {
        val store = IncidentStore()
        repeat(50) { idx ->
            val (i, d) = stub("incident $idx", Severity.SEV2, "svc")
            store.save(i, d)
        }
        assertEquals(20, store.search().size, "default limit = 20")
        assertEquals(5, store.search(limit = 5).size)
        assertEquals(50, store.search(limit = 100).size, "limit > 件数なら全件")
    }

    @Test
    fun `detectedAfter フィルタで指定時刻以降のみ返す`() {
        val store = IncidentStore()
        val (i, d) = stub("recent", Severity.SEV1, "x")
        store.save(i, d)
        // detectedAt は now、それより後の cutoff だと 0 件、前なら 1 件
        val cutoffFuture = i.detectedAt.plusSeconds(60)
        val cutoffPast = i.detectedAt.minusSeconds(60)
        assertEquals(0, store.search(detectedAfter = cutoffFuture).size)
        assertEquals(1, store.search(detectedAfter = cutoffPast).size)
    }

    @Test
    fun `detectedBefore フィルタで指定時刻以前のみ返す`() {
        val store = IncidentStore()
        val (i, d) = stub("old", Severity.SEV2, "y")
        store.save(i, d)
        val cutoffFuture = i.detectedAt.plusSeconds(60)
        val cutoffPast = i.detectedAt.minusSeconds(60)
        assertEquals(1, store.search(detectedBefore = cutoffFuture).size)
        assertEquals(0, store.search(detectedBefore = cutoffPast).size)
    }

    @Test
    fun `複数フィルタ組合せ severity service keyword の AND`() {
        val store = IncidentStore()
        val (i1, d1) = stub("auth 5xx burst", Severity.SEV1, "auth")
        val (i2, d2) = stub("auth latency", Severity.SEV2, "auth")
        val (i3, d3) = stub("billing 5xx", Severity.SEV1, "billing")
        store.save(i1, d1)
        store.save(i2, d2)
        store.save(i3, d3)
        val r = store.search(service = "auth", severity = Severity.SEV1, keyword = "5xx")
        assertEquals(1, r.size)
        assertEquals("auth 5xx burst", r[0].incident.title)
    }
}
