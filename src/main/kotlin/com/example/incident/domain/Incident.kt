package com.example.incident.domain

import java.time.Instant
import java.util.UUID

enum class Severity { SEV1, SEV2, SEV3, SEV4 }

data class Incident(
    val id: UUID,
    val title: String,
    val description: String,
    val severity: Severity,
    val service: String,
    val detectedAt: Instant,
) {
    companion object {
        fun create(
            title: String,
            description: String,
            severity: Severity,
            service: String,
            now: Instant = Instant.now(),
        ): Incident = Incident(UUID.randomUUID(), title.trim(), description.trim(), severity, service.trim(), now)
    }
}

data class Recommendation(
    val title: String,
    val rationale: String,
    val confidence: Double,
)

data class Diagnosis(
    val incidentId: UUID,
    val hypothesis: String,
    val recommendations: List<Recommendation>,
    val mcpContextFiles: List<String>,
    val llmModel: String,
    val trace: List<TraceStep>,
)

data class TraceStep(
    val step: Int,
    val kind: String,
    val summary: String,
)
