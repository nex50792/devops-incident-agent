package com.example.incident.service

import com.example.incident.domain.Diagnosis
import com.example.incident.domain.Incident
import com.example.incident.domain.Severity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 過去の incident + diagnosis を保存して検索可能にする in-memory store。
 * production では Postgres + pgvector で類似度検索 (RAG) する想定 (Phase 2)。
 *
 * 検索は「service / severity / 部分文字列マッチ」の単純フィルタ。
 * ベクトル検索は外部 backend (Pinecone / Qdrant 等) と連携する Phase 2 で実装。
 */
class IncidentStore {
    data class Entry(
        val incident: Incident,
        val diagnosis: Diagnosis,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    fun save(
        incident: Incident,
        diagnosis: Diagnosis,
    ) {
        entries[incident.id] = Entry(incident, diagnosis)
    }

    fun get(id: UUID): Entry? = entries[id]

    fun search(
        service: String? = null,
        severity: Severity? = null,
        keyword: String? = null,
        detectedAfter: java.time.Instant? = null,
        detectedBefore: java.time.Instant? = null,
        limit: Int = 20,
    ): List<Entry> =
        entries.values
            .filter { e -> service == null || e.incident.service == service }
            .filter { e -> severity == null || e.incident.severity == severity }
            .filter { e ->
                keyword == null ||
                    e.incident.title.contains(keyword, ignoreCase = true) ||
                    e.incident.description.contains(keyword, ignoreCase = true) ||
                    e.diagnosis.hypothesis.contains(keyword, ignoreCase = true)
            }.filter { e -> detectedAfter == null || e.incident.detectedAt.isAfter(detectedAfter) }
            .filter { e -> detectedBefore == null || e.incident.detectedAt.isBefore(detectedBefore) }
            .sortedByDescending { it.incident.detectedAt }
            .take(limit)

    fun count(): Int = entries.size
}
