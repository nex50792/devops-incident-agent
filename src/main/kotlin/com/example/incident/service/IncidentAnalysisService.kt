package com.example.incident.service

import com.example.incident.domain.Diagnosis
import com.example.incident.domain.Incident
import com.example.incident.domain.TraceStep
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * 「初回コンテキスト取得 → 仮説生成 → 必要なら convention 取得 → 仮説リファイン」
 * という最小 ReAct 風のループでインシデントを解析する。
 *
 * シンプルな設計選択:
 * - 反復回数は最大 2 (initial + 1 refinement) にハードコード。max-step 問題を回避。
 * - 「リファインが必要か」判定は、初回仮説に「必要」「不明」「unknown」「unclear」が含まれるか
 *   または confidence の最大値が < 0.5 (Stub LLM では severity から決まる)。
 * - trace を Diagnosis に同梱して観測可能性を担保 (将来 OpenTelemetry span へ移行可能な構造)。
 */
class IncidentAnalysisService(
    private val mcp: McpContextProvider,
    private val llm: LlmConnector,
    private val repoPath: String,
    private val meterRegistry: MeterRegistry,
    private val store: IncidentStore? = null,
) {
    private val log = LoggerFactory.getLogger(IncidentAnalysisService::class.java)

    suspend fun analyze(incident: Incident): Diagnosis {
        MDC.put("incident_id", incident.id.toString())
        MDC.put("severity", incident.severity.name)
        MDC.put("service", incident.service)
        val overall = Timer.start(meterRegistry)
        try {
            log.info("agent.start: severity={} service={}", incident.severity, incident.service)
            val trace = mutableListOf<TraceStep>()

            val tour =
                recordStep(trace, 1, "mcp:codebase_tour") {
                    mcp.fetchTour(repoPath, topN = 5)
                }
            log.debug("mcp.tour: files={}", tour.files.size)

            // 過去の類似 incident を最大 3 件 hint として LLM プロンプトに混ぜる
            val pastHints =
                store
                    ?.search(
                        service = incident.service,
                        severity = incident.severity,
                        limit = 3,
                    ).orEmpty()
            val pastSnippet =
                if (pastHints.isEmpty()) {
                    ""
                } else {
                    "## 過去類似インシデント (同 service / severity)\n" +
                        pastHints.joinToString("\n") { e ->
                            "- ${e.incident.title}: ${e.diagnosis.hypothesis.take(120)}"
                        }
                }
            if (pastSnippet.isNotBlank()) {
                trace += TraceStep(trace.size + 1, "store:hint", "${pastHints.size} past incidents")
            }

            var snippet = listOf(tour.rawSnippet, pastSnippet).filter { it.isNotBlank() }.joinToString("\n\n")
            var analysis =
                recordStep(trace, 2, "llm:analyze") {
                    llm.analyze(incident, snippet)
                }
            log.info(
                "llm.initial: model={} confidence={}",
                analysis.model,
                analysis.recommendations.maxOfOrNull { it.confidence } ?: 0.0,
            )

            if (needsRefinement(analysis)) {
                log.info("agent.refine: triggered (low confidence or unclear hypothesis)")
                val convention =
                    recordStep(trace, 3, "mcp:convention_extract") {
                        mcp.fetchConvention(repoPath)
                    }
                if (convention.rawSnippet.isNotBlank()) {
                    snippet = listOf(snippet, convention.rawSnippet).filter { it.isNotBlank() }.joinToString("\n\n")
                    analysis =
                        recordStep(trace, 4, "llm:refine") {
                            llm.analyze(incident, snippet)
                        }
                    log.info(
                        "llm.refined: confidence={}",
                        analysis.recommendations.maxOfOrNull { it.confidence } ?: 0.0,
                    )
                }
            }

            meterRegistry
                .counter(
                    "incident.analyze.completed",
                    listOf(
                        Tag.of("severity", incident.severity.name),
                        Tag.of("refined", (trace.any { it.kind == "llm:refine" }).toString()),
                    ),
                ).increment()

            log.info("agent.done: trace_steps={} model={}", trace.size, analysis.model)
            val diagnosis =
                Diagnosis(
                    incidentId = incident.id,
                    hypothesis = analysis.hypothesis,
                    recommendations = analysis.recommendations,
                    mcpContextFiles = tour.files,
                    llmModel = analysis.model,
                    trace = trace,
                )
            store?.save(incident, diagnosis)
            return diagnosis
        } finally {
            overall.stop(meterRegistry.timer("incident.analyze.duration", "severity", incident.severity.name))
            MDC.remove("incident_id")
            MDC.remove("severity")
            MDC.remove("service")
        }
    }

    private suspend inline fun <T> recordStep(
        trace: MutableList<TraceStep>,
        step: Int,
        kind: String,
        block: () -> T,
    ): T {
        MDC.put("trace_step", step.toString())
        MDC.put("trace_kind", kind)
        val (metricName, tagKey) =
            if (kind.startsWith("mcp:")) {
                "mcp.call.duration" to "tool"
            } else {
                "llm.call.duration" to "phase"
            }
        val tagValue = kind.substringAfter(":")
        val timer = Timer.start(meterRegistry)
        try {
            val result = block()
            trace += TraceStep(step, kind, summarize(result))
            return result
        } finally {
            timer.stop(meterRegistry.timer(metricName, tagKey, tagValue))
            MDC.remove("trace_step")
            MDC.remove("trace_kind")
        }
    }

    private fun summarize(any: Any?): String =
        when (any) {
            is McpContext -> "files=${any.files.size}"
            is ConventionContext -> "snippet=${any.rawSnippet.length} chars"
            is LlmAnalysis -> "hypothesis=${any.hypothesis.take(60)}…"
            else -> any.toString().take(80)
        }

    private fun needsRefinement(a: LlmAnalysis): Boolean {
        val low = a.recommendations.maxOfOrNull { it.confidence } ?: 0.0
        if (low < 0.5) return true
        val text = (a.hypothesis + " " + a.recommendations.joinToString { it.rationale }).lowercase()
        return REFINE_HINTS.any { text.contains(it) }
    }

    private companion object {
        val REFINE_HINTS = listOf("必要", "不明", "unknown", "unclear", "needs more")
    }
}
