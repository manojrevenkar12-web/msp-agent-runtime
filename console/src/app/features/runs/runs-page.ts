import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api';
import { RunDetail } from '../../core/models';
import { agentName, eventName, when } from '../../shared/format';
import { loadable } from '../../shared/loadable';
import { StateView } from '../../shared/state';
import { StatusMark } from '../../shared/status-mark';
import { RunDetailView } from './run-detail';

@Component({
  selector: 'app-runs',
  imports: [StateView, StatusMark, RunDetailView],
  template: `
    <h1>Runs</h1>
    <div class="filters">
      <label>Agent
        <select [value]="agent()" (change)="agent.set($any($event.target).value)">
          <option value="">all</option>
          @for (a of agents; track a) { <option [value]="a">{{ agentName[a] }}</option> }
        </select>
      </label>
      <label>Status
        <select [value]="status()" (change)="status.set($any($event.target).value)">
          <option value="">all</option>
          <option value="DONE">done</option>
          <option value="RETRY">retrying</option>
          <option value="WAITING_APPROVAL">waiting for a person</option>
          <option value="FAILED">failed</option>
          <option value="DEAD">gave up</option>
        </select>
      </label>
    </div>
    <div class="split" [class.open]="!!selected()">
      <app-state what="The runs" [loading]="runs.loading()" [error]="runs.error()" (retry)="runs.reload()"
        [empty]="shown().length === 0" emptyText="No run matches these filters.">
        <table class="data">
          <thead>
            <tr><th scope="col">Agent</th><th scope="col" class="wide">Started by</th><th scope="col">Ticket or customer</th>
              <th scope="col">Status</th><th scope="col" class="r">Attempts</th><th scope="col" class="wide">At</th></tr>
          </thead>
          <tbody>
            @for (r of shown(); track r.id) {
              <tr [class.sel]="r.id === selectedId()" (click)="select(r.id)" (keydown.enter)="select(r.id)" tabindex="0">
                <td>{{ agentName[r.agentId] ?? r.agentId }}</td>
                <td class="condensed wide">{{ eventName[r.eventType] ?? r.eventType }}</td>
                <td>{{ r.subject }}</td>
                <td><app-status [status]="r.status" /></td>
                <td class="r num" [class.warn]="r.attempts > 1">{{ r.attempts }}</td>
                <td class="condensed muted wide">{{ when(r.updatedAt) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </app-state>
      @if (selectedId()) {
        <aside aria-label="Run">
          <button class="btn close" type="button" (click)="select(null)">Close</button>
          <app-state what="The run" [loading]="detailLoading()" [error]="detailError()" (retry)="loadDetail()">
            @if (selected(); as d) { <app-run-detail [run]="d" /> }
          </app-state>
        </aside>
      }
    </div>
  `,
  styles: `
    :host { display: grid; gap: var(--space-5); }
    .filters { display: flex; gap: var(--space-5); font-size: var(--text-sm); }
    .filters label { display: grid; gap: var(--space-1); color: var(--ink-2); }
    select { border: 1px solid var(--rule-strong); border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2); background: var(--sheet); }
    .split { display: grid; gap: var(--space-6); }
    .split.open { grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr); }
    tbody tr { cursor: pointer; }
    td { white-space: nowrap; }
    .split.open .wide { display: none; }
    tbody tr:hover, tbody tr.sel { background: var(--sheet); }
    tr.sel td:first-child { box-shadow: inset 3px 0 0 var(--petrol); }
    .warn { color: var(--retry); font-weight: var(--weight-medium); }
    aside { position: sticky; top: var(--space-5); align-self: start; max-height: calc(100vh - 2 * var(--space-5)); overflow-y: auto;
      display: grid; gap: var(--space-3); padding-right: var(--space-2); }
    .close { justify-self: end; }
    @media (max-width: 1100px) { .split.open { grid-template-columns: 1fr; } }
  `,
})
export class RunsPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly agentName = agentName;
  readonly eventName = eventName;
  readonly when = when;
  readonly agents = ['triage', 'time-capture', 'concierge', 'billing'];
  readonly agent = signal('');
  readonly status = signal('');
  readonly runs = loadable(() => this.api.runs({ limit: 500 }));
  readonly shown = computed(() =>
    (this.runs.value() ?? []).filter((r) => (!this.agent() || r.agentId === this.agent()) && (!this.status() || r.status === this.status())),
  );
  private readonly query = toSignal(this.route.queryParamMap);
  readonly selectedId = computed(() => this.query()?.get('run') ?? null);
  readonly selected = signal<RunDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (this.selectedId()) void this.loadDetail();
      else this.selected.set(null);
    });
  }

  async loadDetail(): Promise<void> {
    const id = this.selectedId();
    if (!id) return;
    this.detailLoading.set(true);
    this.detailError.set(null);
    try {
      this.selected.set(await this.api.run(id));
    } catch (e) {
      this.detailError.set(e instanceof Error ? e.message : 'Could not load the run.');
    } finally {
      this.detailLoading.set(false);
    }
  }

  select(id: string | null): void {
    void this.router.navigate([], { queryParams: { run: id }, queryParamsHandling: 'merge' });
  }
}
