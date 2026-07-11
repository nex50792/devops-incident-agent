'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export function IncidentList() {
  const { data, isLoading } = useQuery({ queryKey: ['incidents'], queryFn: () => api.listIncidents({ limit: 20 }) });
  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: api.getStats });

  return (
    <div className="space-y-3 rounded-lg border border-slate-700 bg-slate-900/60 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-300">履歴 (直近 20 件)</h3>
        {stats && <span className="text-xs text-slate-400">全 {stats.total} 件</span>}
      </div>
      {isLoading && <p className="text-sm text-slate-400">読み込み中...</p>}
      <ul className="space-y-2">
        {data?.map((i) => (
          <li key={i.id} className="rounded border border-slate-800 bg-slate-950 p-2 text-sm">
            <div className="flex items-center justify-between">
              <span className="font-medium">{i.title}</span>
              <span className="text-xs text-slate-400">
                {i.severity} / {i.service}
              </span>
            </div>
            <p className="mt-1 line-clamp-2 text-xs text-slate-400">{i.hypothesis}</p>
          </li>
        ))}
        {data?.length === 0 && <p className="text-sm text-slate-500">まだ履歴なし</p>}
      </ul>
    </div>
  );
}
