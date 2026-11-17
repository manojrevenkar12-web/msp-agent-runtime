// The service API's shapes (docs/api.md). demo.json uses the same ones.

export type RunStatus = 'PENDING' | 'RUNNING' | 'RETRY' | 'WAITING_APPROVAL' | 'DONE' | 'FAILED' | 'DEAD';

export interface RunSummary {
  id: string;
  agentId: string;
  agentVersion: number;
  status: RunStatus;
  attempts: number;
  eventType: string;
  eventId: string;
  subject: string;
  occurredAt: string;
  createdAt: string;
  updatedAt: string;
  nextAttemptAt: string | null;
  error: string | null;
}

export interface Step {
  seq: number;
  name: string;
  at: string;
  json: string;
}

export interface Span {
  spanId: string;
  parentId: string | null;
  name: string;
  start: string;
  end: string;
  attributes: Record<string, string>;
  error: boolean;
}

export interface RunDetail extends RunSummary {
  steps: Step[];
  spans: Span[];
}

export interface Param {
  name: string;
  type: 'INT' | 'CENTS' | 'BOOL';
  defaultValue: number;
  min: number;
  max: number;
  label: string;
  value: number | boolean;
}

export interface Version {
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  config: Record<string, number | boolean>;
  createdBy: string;
  createdAt: string;
  note: string | null;
}

export interface Agent {
  id: string;
  title: string;
  description: string;
  eventTypes: string[];
  params: Param[];
  published: number | null;
  versions: Version[];
}

export interface InvoiceLine {
  kind: 'FLAT_FEE' | 'PREPAID_DRAW' | 'LABOUR' | 'TRAVEL';
  ticketId: string | null;
  minutes: number;
  amountCents: number;
  text: string;
}

export interface Approval {
  id: string;
  runId: string;
  agentId: string;
  name: string;
  reason: string;
  payload: { customerId: string; month: string; lines: InvoiceLine[]; externalRef: string };
  status: string;
  createdAt: string;
}

export interface TestReport {
  agentId: string;
  version: number;
  at: string;
  cases: { id: string; note: string | null; eventId: string; passed: boolean; status: string;
    mismatches: { field: string; expected: unknown; actual: unknown }[] }[];
  replayed: number;
  changes: { runId: string; subject: string; fields: { field: string; before: unknown; after: unknown }[] }[];
  passed: boolean;
}

export type DesignScores = Record<string, number>;

export interface Evaluation {
  manual: { score: DesignScores; runtime: Record<string, number> };
  modelDecides: { score: DesignScores; runtime: Record<string, number> };
  platform: { score: DesignScores; runtime: Record<string, number> };
}

export interface Overview {
  tenant: string;
  tenantName: string;
  user: string;
  role: string;
  runsLastDay: Record<string, Record<string, number>>;
  waitingApprovals: number;
  retriedLastDay: number;
  usageLastDay: { inputTokens: number; outputTokens: number; costMicros: number; modelCalls: number };
  latencyLastDay: { p50Seconds: number; p95Seconds: number };
  evaluation?: Evaluation;
  evaluationSeeds?: number[];
  note?: string;
}
