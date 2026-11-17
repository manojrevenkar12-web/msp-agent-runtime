import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api } from '../../core/api';
import { RunSummary } from '../../core/models';
import { count, eurosExact, seconds } from '../../shared/format';
import { loadable } from '../../shared/loadable';
import { StateView } from '../../shared/state';
import { Compare } from './compare';
import { Switchboard } from './switchboard';

@Component({
  selector: 'app-overview',
  imports: [StateView, Switchboard, Compare],
  template: `
    <app-state what="The overview" [loading]="overview.loading()" [error]="overview.error()" (retry)="overview.reload()"
      [rows]="[40, 90, 90, 90]">
      @if (overview.value(); as o) {
        <header>
          <h1>{{ o.tenantName }}</h1>
          <p class="ledger">
            The agents finished <strong class="num">{{ count(done()) }}</strong> runs{{ demo() ? ' in the simulated month' : ' in the last day' }}.
            <strong class="num">{{ count(o.retriedLastDay) }}</strong> needed another attempt after a crash or a throttled model and still wrote
            everything once; <strong class="num">{{ o.waitingApprovals }}</strong> wait for a person.
            The model cost <strong class="num">{{ cost(o.usageLastDay.costMicros) }}</strong> over
            {{ count(o.usageLastDay.modelCalls) }} calls; half the runs finished within
            <strong class="num">{{ seconds(o.latencyLastDay.p50Seconds) }}</strong> of their event.
          </p>
        </header>
      }
    </app-state>

    <section aria-labelledby="board-h">
      <h2 id="board-h">Runs by agent</h2>
      <app-state what="The runs" [loading]="runs.loading()" [error]="runs.error()" (retry)="runs.reload()"
        [empty]="(runs.value() ?? []).length === 0" emptyText="No runs yet. Runs appear here as soon as the first webhook arrives.">
        <app-switchboard [runs]="board()" (open)="openRun($event)" />
      </app-state>
    </section>

    @if (overview.value()?.evaluation; as e) {
      <section aria-labelledby="cmp-h">
        <h2 id="cmp-h">The same month, three ways</h2>
        <p class="muted lede">
          Simulated months at a service provider with 60 customers (seeds {{ overview.value()?.evaluationSeeds?.join(', ') }}), each
          run with the status quo, with a model acting on its own answers, and with this runtime, under the same duplicate
          and late webhooks and the same crashes.
        </p>
        <app-compare [evaluation]="e" />
      </section>
    }
  `,
  styles: `
    header { display: grid; gap: var(--space-4); margin-bottom: var(--space-7); }
    .ledger { font-size: var(--text-lg); line-height: 1.45; max-width: 60ch; }
    .ledger strong { font-weight: var(--weight-heading); color: var(--petrol); }
    section { margin-bottom: var(--space-8); display: grid; gap: var(--space-4); }
    .lede { font-size: var(--text-sm); }
  `,
})
export class Overview {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  readonly count = count;
  readonly seconds = seconds;
  readonly demo = this.api.demo;
  readonly overview = loadable(() => this.api.overview());
  readonly runs = loadable(() => this.api.runs({ limit: 400 }));
  private readonly arrived = signal<RunSummary[]>([]);

  readonly board = computed(() => {
    const seen = new Set<string>();
    return [...this.arrived(), ...(this.runs.value() ?? [])].filter((r) => !seen.has(r.id) && !!seen.add(r.id));
  });

  readonly done = computed(() => {
    const o = this.overview.value();
    if (!o) return 0;
    return Object.values(o.runsLastDay).reduce((a, m) => a + (m['DONE'] ?? 0), 0);
  });

  constructor() {
    const sub = this.api.live().subscribe({
      next: (r) => this.arrived.update((xs) => [r, ...xs.filter((x) => x.id !== r.id)].slice(0, 200)),
      error: () => undefined,
    });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  cost(micros: number): string {
    return eurosExact(Math.round(micros / 1e4));
  }

  openRun(id: string): void {
    void this.router.navigate(['/runs'], { queryParams: { run: id } });
  }
}
