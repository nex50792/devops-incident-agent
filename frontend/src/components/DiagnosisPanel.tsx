'use client';

import type { Diagnosis } from '@/lib/api';

const KIND_LABEL: Record<string, string> = {
  'agent:tool_use': 'ツール実行',
  'agent:final': '最終回答',
  'agent:final_forced': '最終回答 (上限到達)',
  'agent:max_steps': '調査ステップ上限',
  'agent:decide': '思考',
  'mcp:codebase_tour': 'コードベース概観',
  'llm:analyze': 'LLM 解析',
  'store:hint': '過去履歴の参照',
};

export function DiagnosisPanel({ diagnosis }: { diagnosis: Diagnosis | null }) {
  if (!diagnosis) {
    return (
      <div className="rounded-lg border border-slate-700 bg-slate-900/60 p-4 text-sm text-slate-400">
        まだ障害は解析されていません。左のフォームから作成してください。
      </div>
    );
  }
  return (
    <div className="space-y-3 rounded-lg border border-slate-700 bg-slate-900/60 p-4">
      <div className="flex items-center justify-between text-xs text-slate-400">
        <span>障害ID: {diagnosis.incidentId}</span>
        <span>使用モデル: {diagnosis.llmModel}</span>
      </div>
      <div>
        <h3 className="text-sm font-semibold text-slate-300">仮説</h3>
        <p className="mt-1 whitespace-pre-wrap text-sm">{diagnosis.hypothesis}</p>
      </div>
      <div>
        <h3 className="text-sm font-semibold text-slate-300">推奨アクション</h3>
        <ol className="mt-1 list-decimal space-y-1 pl-5 text-sm">
          {diagnosis.recommendations.map((r, i) => (
            <li key={i}>
              <span className="font-medium">{r.title}</span>{' '}
              <span className="text-slate-400">(確信度 {(r.confidence * 100).toFixed(0)}%)</span>
              <div className="text-slate-400">{r.rationale}</div>
            </li>
          ))}
        </ol>
      </div>
      {diagnosis.trace.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-300">AI の調査手順</h3>
          <ul className="mt-1 space-y-1 text-xs text-slate-400">
            {diagnosis.trace.map((t) => (
              <li key={t.step}>
                {t.step}. {KIND_LABEL[t.kind] ?? t.kind}: {t.summary}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
