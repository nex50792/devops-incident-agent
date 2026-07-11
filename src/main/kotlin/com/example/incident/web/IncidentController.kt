package com.example.incident.web

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation
import com.example.incident.domain.Severity
import com.example.incident.service.IncidentAnalysisService
import com.example.incident.service.IncidentStore
import com.example.incident.service.ReActAgentService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class IncidentRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank @field:Size(max = 4000) val description: String,
    val severity: Severity,
    @field:NotBlank @field:Size(max = 100) val service: String,
)

data class RecommendationView(
    val title: String,
    val rationale: String,
    val confidence: Double,
)

data class DiagnosisResponse(
    val incidentId: UUID,
    val hypothesis: String,
    val recommendations: List<RecommendationView>,
    val mcpContextFiles: List<String>,
    val llmModel: String,
    val trace: List<TraceStepView>,
)

data class TraceStepView(
    val step: Int,
    val kind: String,
    val summary: String,
)

data class IncidentSummary(
    val id: UUID,
    val title: String,
    val severity: Severity,
    val service: String,
    val hypothesis: String,
    val llmModel: String,
)

data class IncidentStats(
    val total: Int,
    val bySeverity: Map<Severity, Int>,
    val byService: Map<String, Int>,
    val byLlmModel: Map<String, Int>,
)

@RestController
@RequestMapping("/incidents")
class IncidentController(
    private val service: IncidentAnalysisService,
    private val agentService: ReActAgentService,
    private val store: IncidentStore,
) {
    @PostMapping
    fun trigger(
        @Valid @RequestBody req: IncidentRequest,
    ): ResponseEntity<DiagnosisResponse> {
        val incident = Incident.create(req.title, req.description, req.severity, req.service)
        val diagnosis = runBlocking { service.analyze(incident) }
        return ResponseEntity.ok(toView(diagnosis))
    }

    /** ReAct full エージェントループで解析。ツール選択を LLM に任せる代わりに max_steps で制限。 */
    @PostMapping("/agent")
    fun triggerAgent(
        @Valid @RequestBody req: IncidentRequest,
    ): ResponseEntity<DiagnosisResponse> {
        val incident = Incident.create(req.title, req.description, req.severity, req.service)
        val diagnosis = runBlocking { agentService.analyze(incident) }
        return ResponseEntity.ok(toView(diagnosis))
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    fun getOne(
        @org.springframework.web.bind.annotation.PathVariable id: UUID,
    ): ResponseEntity<DiagnosisResponse> {
        val entry = store.get(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(toView(entry.diagnosis))
    }

    @org.springframework.web.bind.annotation.GetMapping("/summary")
    fun summary(): ResponseEntity<IncidentStats> {
        val all = store.search(limit = Int.MAX_VALUE)
        val stats =
            IncidentStats(
                total = all.size,
                bySeverity = all.groupingBy { it.incident.severity }.eachCount(),
                byService = all.groupingBy { it.incident.service }.eachCount(),
                byLlmModel = all.groupingBy { it.diagnosis.llmModel }.eachCount(),
            )
        return ResponseEntity.ok(stats)
    }

    @org.springframework.web.bind.annotation.GetMapping
    fun search(
        @org.springframework.web.bind.annotation.RequestParam(required = false) service: String?,
        @org.springframework.web.bind.annotation.RequestParam(required = false) severity: Severity?,
        @org.springframework.web.bind.annotation.RequestParam(required = false) keyword: String?,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "20") limit: Int,
    ): ResponseEntity<List<IncidentSummary>> {
        val results =
            store.search(service = service, severity = severity, keyword = keyword, limit = limit).map { e ->
                IncidentSummary(
                    id = e.incident.id,
                    title = e.incident.title,
                    severity = e.incident.severity,
                    service = e.incident.service,
                    hypothesis = e.diagnosis.hypothesis,
                    llmModel = e.diagnosis.llmModel,
                )
            }
        return ResponseEntity.ok(results)
    }

    private fun toView(diagnosis: com.example.incident.domain.Diagnosis): DiagnosisResponse =
        DiagnosisResponse(
            incidentId = diagnosis.incidentId,
            hypothesis = diagnosis.hypothesis,
            recommendations = diagnosis.recommendations.map(::view),
            mcpContextFiles = diagnosis.mcpContextFiles,
            llmModel = diagnosis.llmModel,
            trace = diagnosis.trace.map { TraceStepView(it.step, it.kind, it.summary) },
        )

    private fun view(r: Recommendation): RecommendationView = RecommendationView(r.title, r.rationale, r.confidence)
}
