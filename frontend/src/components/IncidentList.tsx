'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { KIND_LABEL } from './DiagnosisPanel';

function IncidentDetail({ id }: { id: string }) {
  const { data, isLoading } = useQuery({ queryKey: ['incident', id], queryFn: () => api.getIncident(id) });
  if (isLoading) return <p className="mt-2 text-xs text-slate-400">読み込み中...</p>;
  if (!data) return null;
  return (
    <div className="mt-2 space-y-2 border-t border-slate-800 pt-2">
      <p className="whitespace-pre-wrap text-xs text-slate-300">{data.hypothesis}</p>
      <div>
        <h4 className="text-xs font-semibold text-slate-300">推奨アクション</h4>
        <ol className="mt-1 list-decimal space-y-1 pl-4 text-xs">
          {data.recommendations.map((r, i) => (
            <li key={i}>
              <span className="font-medium">{r.title}</span>{' '}
              <span className="text-slate-400">(確信度 {(r.confidence * 100).toFixed(0)}%)</span>
              <div className="text-slate-400">{r.rationale}</div>
            </li>
          ))}
        </ol>
      </div>
      {data.trace.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-slate-300">AI の調査手順</h4>
          <ul className="mt-1 space-y-0.5 text-xs text-slate-400">
            {data.trace.map((t) => (
              <li key={t.step}>
                {t.step}. {KIND_LABEL[t.kind] ?? t.kind}: {t.summary}
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className="text-right text-xs text-slate-500">使用モデル: {data.llmModel}</p>
    </div>
  );
}

export function IncidentList() {
  const { data, isLoading } = useQuery({ queryKey: ['incidents'], queryFn: () => api.listIncidents({ limit: 20 }) });
  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: api.getStats });
  const [openId, setOpenId] = useState<string | null>(null);

  return (
    <div className="space-y-3 rounded-lg border border-slate-700 bg-slate-900/60 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-300">履歴 (直近 20 件)</h3>
        {stats && <span className="text-xs text-slate-400">全 {stats.total} 件</span>}
      </div>
      {isLoading && <p className="text-sm text-slate-400">読み込み中...</p>}
      <ul className="space-y-2">
        {data?.map((i) => {
          const open = openId === i.id;
          return (
            <li key={i.id} className="rounded border border-slate-800 bg-slate-950 p-2 text-sm">
              <button
                type="button"
                aria-expanded={open}
                onClick={() => setOpenId(open ? null : i.id)}
                className="w-full text-left"
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium">{i.title}</span>
                  <span className="shrink-0 pl-2 text-xs text-slate-400">
                    {i.severity} / {i.service} {open ? '▲' : '▼'}
                  </span>
                </div>
                {!open && <p className="mt-1 line-clamp-2 text-xs text-slate-400">{i.hypothesis}</p>}
              </button>
              {open && <IncidentDetail id={i.id} />}
            </li>
          );
        })}
        {data?.length === 0 && <p className="text-sm text-slate-500">まだ履歴なし</p>}
      </ul>
    </div>
  );
}
