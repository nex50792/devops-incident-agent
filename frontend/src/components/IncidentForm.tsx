'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Diagnosis, type Severity } from '@/lib/api';

const SEVERITY_LABEL: Record<Severity, string> = {
  SEV1: 'SEV1 (致命的)',
  SEV2: 'SEV2 (重大)',
  SEV3: 'SEV3 (中程度)',
  SEV4: 'SEV4 (軽微)',
};

export function IncidentForm({ onCreated }: { onCreated?: (d: Diagnosis) => void }) {
  const [title, setTitle] = useState('auth-api で 5xx が急増');
  const [description, setDescription] = useState('Sentry で 5xx が 20%、遅延の p99 は悪化なし');
  const [severity, setSeverity] = useState<Severity>('SEV1');
  const [service, setService] = useState('auth-api');
  const [useAgent, setUseAgent] = useState(true);
  const client = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => api.createIncident({ title, description, severity, service }, useAgent),
    onSuccess: (d) => {
      client.invalidateQueries({ queryKey: ['incidents'] });
      client.invalidateQueries({ queryKey: ['stats'] });
      onCreated?.(d);
    },
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        mutation.mutate();
      }}
      className="space-y-3 rounded-lg border border-slate-700 bg-slate-900/60 p-4"
    >
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col text-sm">
          <span className="text-slate-400">タイトル</span>
          <input
            className="rounded border border-slate-700 bg-slate-950 p-2"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </label>
        <label className="flex flex-col text-sm">
          <span className="text-slate-400">対象サービス</span>
          <input
            className="rounded border border-slate-700 bg-slate-950 p-2"
            value={service}
            onChange={(e) => setService(e.target.value)}
          />
        </label>
        <label className="col-span-2 flex flex-col text-sm">
          <span className="text-slate-400">症状</span>
          <textarea
            rows={3}
            className="rounded border border-slate-700 bg-slate-950 p-2"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <label className="flex flex-col text-sm">
          <span className="text-slate-400">深刻度</span>
          <select
            className="rounded border border-slate-700 bg-slate-950 p-2"
            value={severity}
            onChange={(e) => setSeverity(e.target.value as Severity)}
          >
            {(Object.keys(SEVERITY_LABEL) as Severity[]).map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-end gap-2 text-sm">
          <input
            type="checkbox"
            checked={useAgent}
            onChange={(e) => setUseAgent(e.target.checked)}
          />
          <span>AI に調査ツールを自動で選ばせる</span>
        </label>
      </div>
      <button
        type="submit"
        disabled={mutation.isPending}
        className="rounded bg-indigo-500 px-4 py-2 font-semibold text-white hover:bg-indigo-400 disabled:opacity-50"
      >
        {mutation.isPending ? '解析中...' : '障害を解析する'}
      </button>
      {mutation.isError && (
        <p className="text-sm text-red-400">エラー: {(mutation.error as Error).message}</p>
      )}
    </form>
  );
}
