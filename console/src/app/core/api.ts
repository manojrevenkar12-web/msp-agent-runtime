import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, Subject, firstValueFrom } from 'rxjs';
import { Agent, Approval, Overview, RunDetail, RunSummary, TestReport } from './models';
import { Session } from './session';

interface Demo {
  note: string;
  tenant: string;
  agents: Agent[];
  runs: RunDetail[];
  approvals: Approval[];
  stats: Record<string, unknown>;
  evaluation: Overview['evaluation'];
  evaluationSeeds: number[];
}

/** A failed call with the message the runtime gave, so screens can say what went wrong. */
export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

/**
 * Talks to the runtime when a key is set, otherwise to the simulated month in demo.json. Writes in demo mode change
 * only this page and say so.
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  readonly demo = computed(() => this.session.key() === null);
  private demoData: Promise<Demo> | null = null;
  private readonly demoApprovals = signal<Approval[] | null>(null);

  private headers(): Record<string, string> {
    const k = this.session.key();
    return k ? { 'X-Api-Key': k } : {};
  }

  private async call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
    try {
      return await firstValueFrom(
        this.http.request<T>(method, path, { body, headers: this.headers() }),
      );
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const msg =
          e.status === 0
            ? 'The runtime did not answer. Check that it is running and reachable.'
            : e.status === 401
              ? 'The key was not accepted. Connect again with a valid key.'
              : e.status === 403
                ? 'Your role may not do this.'
                : ((e.error as { error?: string } | null)?.error ?? `The runtime answered ${e.status}.`);
        throw new ApiError(msg, e.status);
      }
      throw e;
    }
  }

  private data(): Promise<Demo> {
    this.demoData ??= firstValueFrom(this.http.get<Demo>('demo.json')).catch(() => {
      this.demoData = null;
      throw new ApiError('The simulated month (demo.json) could not be read.', 0);
    });
    return this.demoData;
  }

  async overview(): Promise<Overview> {
    if (!this.demo()) {
      return this.call<Overview>('GET', '/api/overview');
    }
    const d = await this.data();
    const s = d.stats as Record<string, number> & { runsByAgent: Record<string, Record<string, number>> };
    return {
      tenant: d.tenant,
      tenantName: 'Systemhaus Nord (simulated)',
      user: 'demo',
      role: 'VIEWER',
      runsLastDay: s.runsByAgent,
      waitingApprovals: (await this.approvals()).length,
      retriedLastDay: s['runsRetried'],
      usageLastDay: { inputTokens: s['inputTokens'], outputTokens: s['outputTokens'], costMicros: s['costMicros'],
        modelCalls: s['llmCalls'] },
      latencyLastDay: { p50Seconds: s['latencyP50Seconds'], p95Seconds: s['latencyP95Seconds'] },
      evaluation: d.evaluation,
      evaluationSeeds: d.evaluationSeeds,
      note: d.note,
    };
  }

  async runs(filter: { agent?: string; status?: string; limit?: number } = {}): Promise<RunSummary[]> {
    if (!this.demo()) {
      const q = new URLSearchParams();
      if (filter.agent) q.set('agent', filter.agent);
      if (filter.status) q.set('status', filter.status);
      q.set('limit', String(filter.limit ?? 300));
      return this.call<RunSummary[]>('GET', `/api/runs?${q}`);
    }
    const d = await this.data();
    return d.runs
      .filter((r) => !filter.agent || r.agentId === filter.agent)
      .filter((r) => !filter.status || r.status === filter.status)
      .slice(0, filter.limit ?? 300);
  }

  async run(id: string): Promise<RunDetail> {
    if (!this.demo()) {
      return this.call<RunDetail>('GET', `/api/runs/${encodeURIComponent(id)}`);
    }
    const r = (await this.data()).runs.find((x) => x.id === id);
    if (!r) throw new ApiError('This run is not in the demo data.', 404);
    return r;
  }

  async agents(): Promise<Agent[]> {
    return this.demo() ? (await this.data()).agents : this.call<Agent[]>('GET', '/api/agents');
  }

  private needsRuntime(): never {
    throw new ApiError('Connect a runtime to create, test and publish versions. The demo is read-only here.', 0);
  }

  createVersion(agentId: string, config: Record<string, number | boolean>, note: string): Promise<Agent> {
    if (this.demo()) this.needsRuntime();
    return this.call<Agent>('POST', `/api/agents/${agentId}/versions`, { config, note });
  }

  test(agentId: string, version: number): Promise<TestReport> {
    if (this.demo()) this.needsRuntime();
    return this.call<TestReport>('POST', `/api/agents/${agentId}/versions/${version}/test`);
  }

  publish(agentId: string, version: number): Promise<Agent> {
    if (this.demo()) this.needsRuntime();
    return this.call<Agent>('POST', `/api/agents/${agentId}/versions/${version}/publish`);
  }

  addCase(agentId: string, runId: string, note: string): Promise<unknown> {
    if (this.demo()) this.needsRuntime();
    return this.call('POST', `/api/agents/${agentId}/cases`, { runId, note });
  }

  async approvals(): Promise<Approval[]> {
    if (!this.demo()) {
      return this.call<Approval[]>('GET', '/api/approvals');
    }
    if (this.demoApprovals() === null) {
      this.demoApprovals.set((await this.data()).approvals);
    }
    return this.demoApprovals() ?? [];
  }

  async decide(id: string, approve: boolean): Promise<void> {
    if (!this.demo()) {
      await this.call('POST', `/api/approvals/${encodeURIComponent(id)}`, { approve });
      return;
    }
    this.demoApprovals.update((xs) => (xs ?? []).filter((a) => a.id !== id));
  }

  /**
   * Run changes as they happen: the runtime's WebSocket, or in demo mode the simulated month replayed slowly so the
   * board shows how live runs arrive.
   */
  live(): Observable<RunSummary> {
    const out = new Subject<RunSummary>();
    if (this.demo()) {
      let i = 0;
      let timer: ReturnType<typeof setInterval> | null = null;
      this.data()
        .then((d) => {
          const pool = [...d.runs].reverse();
          timer = setInterval(() => {
            const r = pool[i++ % pool.length];
            out.next({ ...r, updatedAt: new Date().toISOString() });
          }, 2600);
        })
        .catch(() => undefined);
      return new Observable((sub) => {
        const s = out.subscribe(sub);
        return () => {
          s.unsubscribe();
          if (timer) clearInterval(timer);
        };
      });
    }
    return new Observable((sub) => {
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${scheme}://${location.host}/ws/runs?key=${encodeURIComponent(this.session.key() ?? '')}`);
      ws.onmessage = (m) => {
        try {
          const msg = JSON.parse(m.data as string) as { type: string; run: RunSummary };
          if (msg.type === 'run') sub.next(msg.run);
        } catch {
          // ignore a message that is not ours
        }
      };
      ws.onerror = () => sub.error(new ApiError('The live feed closed.', 0));
      return () => ws.close();
    });
  }
}
