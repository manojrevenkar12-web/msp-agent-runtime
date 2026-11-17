import { Agent, Approval, RunDetail } from '../app/core/models';

const run = (id: string, agentId: string, status: RunDetail['status'], attempts = 1, at = '2026-10-30T09:00:00Z'): RunDetail => ({
  id, agentId, agentVersion: 1, status, attempts, eventType: 'ticket.created', eventId: 'e-' + id, subject: 'T1',
  occurredAt: at, createdAt: at, updatedAt: at, nextAttemptAt: null, error: null,
  steps: [{ seq: 1, name: 'decision', at, json: '{"priority":"P2"}' }],
  spans: [{ spanId: 's1', parentId: null, name: 'run ' + agentId, start: at, end: at, attributes: {}, error: false }],
});

const agent: Agent = {
  id: 'triage', title: 'Ticket triage', description: 'Sets priority.', eventTypes: ['ticket.created'], published: 1,
  params: [
    { name: 'minNeighbours', type: 'INT', defaultValue: 3, min: 1, max: 10, label: 'Neighbours needed', value: 3 },
    { name: 'reviewWhenUnsure', type: 'BOOL', defaultValue: 1, min: 0, max: 1, label: 'Review when unsure', value: 1 },
  ],
  versions: [{ version: 1, status: 'PUBLISHED', config: {}, createdBy: 'system', createdAt: '2026-10-01T00:00:00Z', note: null }],
};

const approval: Approval = {
  id: 'a1', runId: 'r9', agentId: 'billing', name: 'invoice', reason: 'total above the approval limit', status: 'PENDING',
  createdAt: '2026-11-01T05:00:00Z',
  payload: { customerId: 'C1045', month: '2026-10', externalRef: 'invoice:C1045:2026-10', lines: [
    { kind: 'FLAT_FEE', ticketId: null, minutes: 0, amountCents: 250000, text: 'Managed services, monthly fee' },
    { kind: 'LABOUR', ticketId: 'T1', minutes: 90, amountCents: 17250, text: 'T1 Laptop' },
  ] },
};

const score = (unbilled: number) => ({ unbilledCents: unbilled, trueRevenueCents: 27_000_000, overbilledCents: 0,
  sessionsEnteredTwice: 0, urgentBreaches: 20, urgentTickets: 150, injectionsThatRaisedPriority: 1, injections: 70,
  updatesLeakingInternalNotes: 0, duplicateInvoices: 0, duplicateUpdates: 0, duplicateTriageWrites: 0, adminMinutes: 900 });

export const demoFixture = {
  note: 'test', tenant: 'systemhaus-nord', agents: [agent], approvals: [approval],
  runs: [run('r1', 'triage', 'DONE'), run('r2', 'time-capture', 'DONE', 2, '2026-10-30T10:00:00Z'),
    run('r9', 'billing', 'WAITING_APPROVAL', 1, '2026-11-01T05:00:00Z')],
  stats: { runsByAgent: { triage: { DONE: 1 }, 'time-capture': { DONE: 1 }, billing: { WAITING_APPROVAL: 1 } },
    runsRetried: 1, inputTokens: 100, outputTokens: 10, costMicros: 2_380_000, llmCalls: 5, latencyP50Seconds: 92,
    latencyP95Seconds: 500 },
  evaluation: { manual: { score: score(5_000_000), runtime: {} }, modelDecides: { score: score(99_000), runtime: {} },
    platform: { score: score(792_000), runtime: {} } },
  evaluationSeeds: [11, 23, 37],
};
