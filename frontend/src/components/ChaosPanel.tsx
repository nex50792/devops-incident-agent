'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

const SCENARIOS: { value: string; label: string }[] = [
  { value: 'OFF', label: '障害なし' },
  { value: 'DB_POOL_TIMEOUT', label: 'DB 接続タイムアウト' },
  { value: 'EXTERNAL_500', label: '外部 API が 500 エラー' },
  { value: 'DEADLOCK', label: 'デッドロック' },
  { value: 'SLOW_QUERY', label: '遅いクエリ (5 秒)' },
];

const SERVICES: { key: string; label: string }[] = [
  { key: 'auth-api', label: '認証 API' },
  { key: 'orders-api', label: '注文 API' },
  { key: 'checkout-api', label: '決済 API' },
];

const ENDPOINTS: { path: 'auth/login' | 'orders' | 'checkout'; label: string }[] = [
  { path: 'auth/login', label: 'ログイン呼び出し' },
  { path: 'orders', label: '注文作成' },
  { path: 'checkout', label: '決済実行' },
];

export function ChaosPanel() {
  const client = useQueryClient();
  const { data: status } = useQuery({ queryKey: ['chaos'], queryFn: api.chaosStatus });

  const set = useMutation({
    mutationFn: ({ service, scenario }: { service: string; scenario: string }) =>
      api.triggerChaos(service, scenario),
    onSuccess: () => client.invalidateQueries({ queryKey: ['chaos'] }),
  });

  const clear = useMutation({
    mutationFn: api.clearChaos,
    onSuccess: () => client.invalidateQueries({ queryKey: ['chaos'] }),
  });

  const call = useMutation({
    mutationFn: (endpoint: 'auth/login' | 'orders' | 'checkout') => api.callDemoEndpoint(endpoint),
  });

  const current = (status?.chaos as Record<string, string> | undefined) ?? {};

  return (
    <div className="space-y-3 rounded-lg border border-slate-700 bg-slate-900/60 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-300">疑似サービスにわざと障害を起こす</h3>
        <button
          className="rounded border border-slate-700 px-2 py-1 text-xs text-slate-400 hover:bg-slate-800"
          onClick={() => clear.mutate()}
        >
          全て解除
        </button>
      </div>
      <div className="space-y-2">
        {SERVICES.map((s) => (
          <div key={s.key} className="flex items-center gap-2 text-sm">
            <span className="w-24 text-slate-400">{s.label}</span>
            <select
              className="rounded border border-slate-700 bg-slate-950 p-1 text-xs"
              value={current[s.key] ?? 'OFF'}
              onChange={(e) => set.mutate({ service: s.key, scenario: e.target.value })}
            >
              {SCENARIOS.map((sc) => (
                <option key={sc.value} value={sc.value}>
                  {sc.label}
                </option>
              ))}
            </select>
          </div>
        ))}
      </div>
      <div>
        <h3 className="mt-2 text-sm font-semibold text-slate-300">疑似サービスを呼び出す (ログが出る)</h3>
        <div className="mt-1 flex flex-wrap gap-2">
          {ENDPOINTS.map((ep) => (
            <button
              key={ep.path}
              className="rounded border border-slate-700 bg-slate-800 px-2 py-1 text-xs hover:bg-slate-700"
              onClick={() => call.mutate(ep.path)}
            >
              {ep.label}
            </button>
          ))}
        </div>
        {call.isSuccess && (
          <pre className="mt-2 overflow-x-auto rounded bg-slate-950 p-2 text-xs text-slate-300">
            {JSON.stringify(call.data, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}
