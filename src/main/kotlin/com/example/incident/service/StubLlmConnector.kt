package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation
import com.example.incident.domain.Severity

/**
 * 決定論的なスタブ実装。実 LLM は Gemini API 経由で差し替える (GeminiLlmConnector)。
 * テスト・ローカル開発・ビルド検証ではこちらを使う。
 */
class StubLlmConnector : LlmConnector {
    override suspend fun analyze(
        incident: Incident,
        mcpContextSnippet: String,
    ): LlmAnalysis {
        val severityHint =
            when (incident.severity) {
                Severity.SEV1, Severity.SEV2 -> "即時対応が必要"
                Severity.SEV3 -> "営業時間内に対応"
                Severity.SEV4 -> "次スプリントで対応"
            }
        val hypothesis =
            buildString {
                append("${incident.service} で「${incident.title}」が起きています。")
                append("深刻度 ${incident.severity} なので $severityHint。")
                if (mcpContextSnippet.isNotBlank()) {
                    append(" コードベースの情報から関連ファイル候補も確認できます。")
                }
            }
        val recs =
            buildList {
                add(
                    Recommendation(
                        title = "直近のデプロイをロールバックできないか確認する",
                        rationale = "1 時間以内にデプロイがあれば戻すのが影響を最小にできる",
                        confidence = 0.7,
                    ),
                )
                add(
                    Recommendation(
                        title = "ダッシュボードで直近 30 分を確認する",
                        rationale = "${incident.service} の遅延とエラー率の変化を見る",
                        confidence = 0.6,
                    ),
                )
                if (incident.severity == Severity.SEV1 || incident.severity == Severity.SEV2) {
                    add(
                        Recommendation(
                            title = "オンコール担当へエスカレーションする",
                            rationale = "深刻度が高いので複数人で対応する必要がある",
                            confidence = 0.9,
                        ),
                    )
                }
            }
        return LlmAnalysis(hypothesis, recs, model = "stub-deterministic-v1")
    }
}
