'use client';

import { useState } from 'react';
import { IncidentForm } from '@/components/IncidentForm';
import { DiagnosisPanel } from '@/components/DiagnosisPanel';
import { IncidentList } from '@/components/IncidentList';
import { ChaosPanel } from '@/components/ChaosPanel';
import type { Diagnosis } from '@/lib/api';

export default function Home() {
  const [diagnosis, setDiagnosis] = useState<Diagnosis | null>(null);
  return (
    <main className="mx-auto max-w-6xl space-y-6 p-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold">devops-incident-agent</h1>
        <p className="text-sm text-slate-400">
          オンコール当番の初動を伴走する障害対応エージェント。左下の疑似サービスにわざと障害を仕込むと、右側で AI が原因を推定して推奨アクションを返します。
        </p>
      </header>

      <div className="grid gap-6 md:grid-cols-2">
        <section className="space-y-4">
          <IncidentForm onCreated={setDiagnosis} />
          <ChaosPanel />
        </section>
        <section className="space-y-4">
          <DiagnosisPanel diagnosis={diagnosis} />
          <IncidentList />
        </section>
      </div>

      <footer className="border-t border-slate-800 pt-3 text-xs text-slate-500">
        ログ集計 (BigQuery)、直近のデプロイ (GitHub API)、過去の障害報告 (pgvector)、コードベース情報 (MCP) の 4 種類の調査ツールを AI に渡し、状況に応じて自動で選ばせます。
      </footer>
    </main>
  );
}
