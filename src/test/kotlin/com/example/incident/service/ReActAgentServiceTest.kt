package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Severity
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReActAgentServiceTest {
    private val agent = StubAgentLlm(repoPath = ".")
    private val registry = SimpleMeterRegistry()
    private val service =
        ReActAgentService(
            mcpClient = null, // mcp client なし → invokeTool は固定 stub レスポンス
            agent = agent,
            meterRegistry = registry,
            maxSteps = 5,
        )

    @Test
    fun `Stub agent は 2 tool calls then final で 3 トレース以上を出す`() =
        runTest {
            val incident = Incident.create("API 5xx", "auth で発生", Severity.SEV1, "auth-api")
            val diagnosis = service.analyze(incident)
            // expected trace: agent:tool_use (codebase_tour) + agent:tool_use (convention_extract) + agent:final
            assertTrue(diagnosis.trace.size >= 3, "trace should have >= 3 steps, got ${diagnosis.trace.size}")
            assertTrue(diagnosis.trace.any { it.kind == "agent:tool_use" && it.summary == "codebase_tour" })
            assertTrue(diagnosis.trace.any { it.kind == "agent:tool_use" && it.summary == "convention_extract" })
            assertTrue(diagnosis.trace.any { it.kind == "agent:final" })
            assertEquals("stub-react-full-v1", diagnosis.llmModel)
        }

    @Test
    fun `SEV1 ReAct full は escalation 推奨を含む`() =
        runTest {
            val incident = Incident.create("x", "y", Severity.SEV1, "svc")
            val diagnosis = service.analyze(incident)
            assertTrue(diagnosis.recommendations.any { it.title.contains("エスカレーション") })
        }

    @Test
    fun `SEV4 ReAct full は escalation を含まない`() =
        runTest {
            val incident = Incident.create("x", "y", Severity.SEV4, "svc")
            val diagnosis = service.analyze(incident)
            assertTrue(diagnosis.recommendations.none { it.title.contains("エスカレーション") })
        }

    @Test
    fun `react_completed カウンターと react_analyze_duration timer が記録される`() =
        runTest {
            val incident = Incident.create("x", "y", Severity.SEV2, "svc")
            service.analyze(incident)
            val timer = registry.find("react.analyze.duration").timer()
            val counter = registry.find("react.completed").counter()
            assertTrue(timer != null && timer.count() == 1L)
            assertTrue(counter != null && counter.count() == 1.0)
        }

    @Test
    fun `maxSteps が低すぎる場合は forced final`() =
        runTest {
            val tightService =
                ReActAgentService(
                    mcpClient = null,
                    agent = agent,
                    meterRegistry = SimpleMeterRegistry(),
                    maxSteps = 1, // 1 ステップで強制 final
                )
            val incident = Incident.create("x", "y", Severity.SEV3, "svc")
            val diagnosis = tightService.analyze(incident)
            assertTrue(diagnosis.trace.any { it.kind == "agent:final_forced" || it.kind == "agent:max_steps" })
        }
}
