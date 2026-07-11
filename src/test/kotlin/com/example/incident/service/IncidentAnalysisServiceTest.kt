package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Severity
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentAnalysisServiceTest {
    private val nullProvider = NullMcpContextProvider()
    private val stubLlm = StubLlmConnector()
    private val registry = SimpleMeterRegistry()
    private val service = IncidentAnalysisService(nullProvider, stubLlm, repoPath = ".", meterRegistry = registry)

    @Test
    fun `analyze 時に IncidentStore へ自動 save される`() =
        runTest {
            val store = IncidentStore()
            val withStore =
                IncidentAnalysisService(
                    nullProvider,
                    stubLlm,
                    repoPath = ".",
                    meterRegistry = SimpleMeterRegistry(),
                    store = store,
                )
            val incident = Incident.create("save-test", "y", Severity.SEV2, "svc")
            withStore.analyze(incident)
            val got = store.get(incident.id)
            assertTrue(got != null, "store should have saved")
            assertEquals("save-test", got.incident.title)
        }

    @Test
    fun `過去類似 incident hint が trace に store hint ステップとして現れる`() =
        runTest {
            val store = IncidentStore()
            val past = Incident.create("過去 5xx", "過去発生", Severity.SEV1, "auth-api")
            val pastDiagnosis =
                com.example.incident.domain.Diagnosis(
                    incidentId = past.id,
                    hypothesis = "過去 DB pool 枯渇",
                    recommendations = emptyList(),
                    mcpContextFiles = emptyList(),
                    llmModel = "stub",
                    trace = emptyList(),
                )
            store.save(past, pastDiagnosis)

            val withStore =
                IncidentAnalysisService(
                    nullProvider,
                    stubLlm,
                    repoPath = ".",
                    meterRegistry = SimpleMeterRegistry(),
                    store = store,
                )
            val current = Incident.create("新 5xx", "今発生", Severity.SEV1, "auth-api")
            val diagnosis = withStore.analyze(current)
            assertTrue(diagnosis.trace.any { it.kind == "store:hint" }, "trace must include store:hint step")
        }

    @Test
    fun `SEV1 incident は escalation 推奨を含む`() =
        runTest {
            val incident = Incident.create("API 5xx 急増", "Login API で 5xx が多発", Severity.SEV1, "auth-api")
            val diagnosis = service.analyze(incident)
            assertEquals(incident.id, diagnosis.incidentId)
            assertTrue(diagnosis.recommendations.any { it.title.contains("エスカレーション") })
            assertEquals("stub-deterministic-v1", diagnosis.llmModel)
        }

    @Test
    fun `SEV4 incident は escalation を含まない`() =
        runTest {
            val incident = Incident.create("低優先のログ警告", "発生頻度低", Severity.SEV4, "logs")
            val diagnosis = service.analyze(incident)
            assertTrue(diagnosis.recommendations.none { it.title.contains("エスカレーション") })
        }

    @Test
    fun `MCP provider 不在でも diagnosis は返る`() =
        runTest {
            val incident = Incident.create("x", "y", Severity.SEV3, "svc")
            val diagnosis = service.analyze(incident)
            assertTrue(diagnosis.mcpContextFiles.isEmpty())
            assertTrue(diagnosis.hypothesis.isNotBlank())
        }

    @Test
    fun `trace に MCP と LLM のステップが順に記録される`() =
        runTest {
            val incident = Incident.create("API 5xx", "auth で発生", Severity.SEV2, "auth")
            val diagnosis = service.analyze(incident)
            assertTrue(diagnosis.trace.isNotEmpty(), "trace must not be empty")
            assertEquals("mcp:codebase_tour", diagnosis.trace.first().kind)
            assertTrue(diagnosis.trace.any { it.kind == "llm:analyze" })
            assertTrue(diagnosis.trace.map { it.step } == diagnosis.trace.map { it.step }.sorted())
        }

    @Test
    fun `analyze 実行で incident_analyze_duration timer と completed counter が記録される`() =
        runTest {
            val incident = Incident.create("metrics", "test", Severity.SEV2, "svc")
            service.analyze(incident)
            val timer = registry.find("incident.analyze.duration").timer()
            val counter = registry.find("incident.analyze.completed").counter()
            assertTrue(timer != null && timer.count() == 1L, "timer should record 1 sample")
            assertTrue(counter != null && counter.count() == 1.0, "completed counter should be 1")
        }

    @Test
    fun `low confidence の場合は refinement step (mcp convention + llm refine) が走る`() =
        runTest {
            val lowConfLlm =
                object : LlmConnector {
                    var calls = 0

                    override suspend fun analyze(
                        incident: Incident,
                        mcpContextSnippet: String,
                    ): LlmAnalysis {
                        calls++
                        return LlmAnalysis(
                            hypothesis = "Hypothesis $calls",
                            recommendations =
                                listOf(
                                    com.example.incident.domain
                                        .Recommendation("rec", "weak", confidence = 0.2),
                                ),
                            model = "test-low-conf",
                        )
                    }
                }
            val provider =
                object : McpContextProvider {
                    override suspend fun fetchTour(
                        repoPath: String,
                        topN: Int,
                    ) = McpContext(listOf("a.kt"), "tour-snippet")

                    override suspend fun fetchConvention(repoPath: String) = ConventionContext("convention-snippet")
                }
            val svc = IncidentAnalysisService(provider, lowConfLlm, repoPath = ".", meterRegistry = SimpleMeterRegistry())
            val diagnosis = svc.analyze(Incident.create("x", "y", Severity.SEV2, "svc"))
            assertEquals(2, lowConfLlm.calls, "LLM should be called twice (initial + refine)")
            assertTrue(diagnosis.trace.any { it.kind == "mcp:convention_extract" })
            assertTrue(diagnosis.trace.any { it.kind == "llm:refine" })
            assertEquals("Hypothesis 2", diagnosis.hypothesis)
        }
}
