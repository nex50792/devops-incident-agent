package com.example.incident.service

import com.example.incident.domain.Incident
import com.example.incident.domain.Recommendation
import com.example.incident.domain.Severity

/**
 * 決定論的な ReAct full スタブ。
 *
 * 動作: history 内の tool_result の数で次アクションを決める。
 * - 0 件: codebase_tour を呼ぶ
 * - 1 件: convention_extract を呼ぶ
 * - 2 件: 最終回答 (severity から組み立て)
 *
 * 実 LLM 呼出なしで「ツール呼出 N 回 → 終了」のループを CI でも検証できる。
 */
class StubAgentLlm(
    private val repoPath: String = ".",
) : AgentLlm {
    override suspend fun decide(
        incident: Incident,
        history: List<AgentTurn>,
    ): AgentDecision {
        val toolResults = history.count { it.role == AgentTurn.Role.TOOL_RESULT }
        return when (toolResults) {
            0 ->
                AgentDecision.CallTool(
                    toolUseId = "stub-tour-1",
                    toolName = "codebase_tour",
                    toolInput = """{"path":"$repoPath","top_n":5}""",
                )
            1 ->
                AgentDecision.CallTool(
                    toolUseId = "stub-conv-2",
                    toolName = "convention_extract",
                    toolInput = """{"path":"$repoPath"}""",
                )
            else -> AgentDecision.Final(buildFinal(incident))
        }
    }

    private fun buildFinal(incident: Incident): LlmAnalysis {
        val severityText =
            when (incident.severity) {
                Severity.SEV1, Severity.SEV2 -> "即時対応が必要"
                Severity.SEV3 -> "営業時間内に対応"
                Severity.SEV4 -> "次スプリントで対応"
            }
        return LlmAnalysis(
            hypothesis =
                "${incident.service} で「${incident.title}」が起きています。$severityText。" +
                    "コードベースの概観と規約を確認した結果、コード構造そのものではなく運用側の変更を疑うのが妥当です。",
            recommendations =
                listOf(
                    Recommendation("直近のデプロイをロールバックできないか確認する", "1 時間以内にデプロイがあれば戻すのが最短", confidence = 0.7),
                    Recommendation("メトリクスを直近 30 分で確認する", "${incident.service} の遅延とエラー率の変化を見る", confidence = 0.6),
                    if (incident.severity == Severity.SEV1 || incident.severity == Severity.SEV2) {
                        Recommendation("オンコール担当へエスカレーションする", "深刻度が高いので複数人で対応する", confidence = 0.9)
                    } else {
                        Recommendation("アプリログを追跡する", "障害発生時刻の前後 5 分でアプリログを絞り込む", confidence = 0.5)
                    },
                ),
            model = "stub-react-full-v1",
        )
    }
}
