const AGENT = process.env.NEXT_PUBLIC_AGENT_API ?? 'http://localhost:8081';
const DEMO = process.env.NEXT_PUBLIC_DEMO_API ?? 'http://localhost:8082';

export type Severity = 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4';

export interface IncidentRequest {
  title: string;
  description: string;
  severity: Severity;
  service: string;
}

export interface Recommendation {
  title: string;
  rationale: string;
  confidence: number;
}

export interface TraceStep {
  step: number;
  kind: string;
  summary: string;
}

export interface Diagnosis {
  incidentId: string;
  hypothesis: string;
  recommendations: Recommendation[];
  mcpContextFiles: string[];
  llmModel: string;
  trace: TraceStep[];
}

export interface IncidentSummary {
  id: string;
  title: string;
  severity: Severity;
  service: string;
  hypothesis: string;
  llmModel: string;
}

export interface IncidentStats {
  total: number;
  bySeverity: Record<Severity, number>;
  byService: Record<string, number>;
  byLlmModel: Record<string, number>;
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return (await res.json()) as T;
}

export const api = {
  createIncident: (req: IncidentRequest, useAgent: boolean) =>
    fetch(`${AGENT}/incidents${useAgent ? '/agent' : ''}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(req),
    }).then(json<Diagnosis>),

  listIncidents: (params: { service?: string; severity?: Severity; keyword?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.service) q.set('service', params.service);
    if (params.severity) q.set('severity', params.severity);
    if (params.keyword) q.set('keyword', params.keyword);
    q.set('limit', String(params.limit ?? 20));
    return fetch(`${AGENT}/incidents?${q}`).then(json<IncidentSummary[]>);
  },

  getStats: () => fetch(`${AGENT}/incidents/summary`).then(json<IncidentStats>),

  triggerChaos: (service: string, scenario: string) =>
    fetch(`${DEMO}/admin/chaos`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ service, scenario }),
    }).then((r) => r.json()),

  clearChaos: () => fetch(`${DEMO}/admin/chaos/clear`, { method: 'POST' }).then((r) => r.json()),

  chaosStatus: () => fetch(`${DEMO}/admin/status`).then((r) => r.json()),

  callDemoEndpoint: (endpoint: 'auth/login' | 'orders' | 'checkout') =>
    fetch(`${DEMO}/api/${endpoint}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ping: Date.now() }),
    }).then((r) => r.json()),
};
