import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Api } from '../../core/api';
import { Agent, Param, TestReport } from '../../core/models';
import { euros, when } from '../../shared/format';

/** An agent's settings within their tested limits, its versions, and the test bench that gates publishing. */
@Component({
  selector: 'app-agent-editor',
  template: `
    @let a = agent();
    <header>
      <h2>{{ a.title }}</h2>
      <p class="muted">{{ a.description }}</p>
      <p class="condensed muted small">Receives: {{ a.eventTypes.join(', ') }}. Published version: {{ a.published ?? 'none' }}.</p>
    </header>

    <form class="settings" (submit)="$event.preventDefault(); saveDraft()">
      <h3>Settings</h3>
      @for (p of a.params; track p.name) {
        <div class="field">
          <label [for]="p.name">{{ p.label }}</label>
          @if (p.type === 'BOOL') {
            <input type="checkbox" [id]="p.name" [checked]="bool(p)" (change)="set(p, $any($event.target).checked)" />
          } @else {
            <input type="number" [id]="p.name" [min]="p.min" [max]="p.max" [value]="num(p)"
              (input)="set(p, $any($event.target).valueAsNumber)" />
          }
          <span class="hint condensed muted">
            {{ p.type === 'CENTS' ? euros(p.min) + ' to ' + euros(p.max) : p.type === 'BOOL' ? '' : p.min + ' to ' + p.max }}
            @if (changed(p)) { · was {{ p.type === 'CENTS' ? euros(+p.value) : p.value }} }
          </span>
        </div>
      }
      <div class="field note">
        <label for="note">Why this change</label>
        <input id="note" type="text" [value]="note()" (input)="note.set($any($event.target).value)" />
      </div>
      <div class="actions">
        <button class="btn primary" type="submit" [disabled]="busy() || !dirty()">Save as a draft version</button>
        @if (message()) { <span role="status" class="msg">{{ message() }}</span> }
      </div>
    </form>

    <h3>Versions</h3>
    <table class="data">
      <thead><tr><th scope="col" class="r">Version</th><th scope="col">State</th><th scope="col">By</th><th scope="col">Note</th><th scope="col"></th></tr></thead>
      <tbody>
        @for (v of versionsNewestFirst(); track v.version) {
          <tr>
            <td class="r num">{{ v.version }}</td>
            <td class="condensed">{{ v.status === 'PUBLISHED' ? 'receives events' : v.status === 'DRAFT' ? 'draft' : 'retired' }}</td>
            <td class="condensed muted">{{ v.createdBy }}, {{ when(v.createdAt) }}</td>
            <td>{{ v.note }}@if (summary(v.config); as sum) {<div class="condensed muted small">{{ sum }}</div>}</td>
            <td class="r">
              @if (v.status === 'DRAFT') {
                <button class="btn" type="button" (click)="test(v.version)" [disabled]="busy()">Test</button>
                <button class="btn primary" type="button" (click)="publish(v.version)"
                  [disabled]="busy() || report()?.version !== v.version || !report()?.passed">Publish</button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>

    @if (report(); as r) {
      <section class="report" [class.fail]="!r.passed" aria-live="polite">
        <h3>Version {{ r.version }}: {{ r.passed ? 'every test case decided as expected' : 'test cases failed, publishing is blocked' }}</h3>
        @if (r.cases.length === 0) {
          <p class="muted">No test cases yet. Keep a run as a test case from its page to add one.</p>
        }
        <ul class="cases">
          @for (c of r.cases; track c.id) {
            <li [class.bad]="!c.passed">
              <strong>{{ c.passed ? 'Passed' : 'Failed' }}</strong> {{ c.note ?? c.eventId }}
              @for (m of c.mismatches; track m.field) {
                <div class="condensed small">{{ m.field }}: expected {{ show(m.expected) }}, got {{ show(m.actual) }}</div>
              }
            </li>
          }
        </ul>
        <p>Replayed on the latest {{ r.replayed === 1 ? 'real event' : r.replayed + ' real events' }}: {{ r.changes.length === 0 ? 'no decision changes.' : r.changes.length === 1 ? 'one decision would change.' : r.changes.length + ' decisions would change.' }}</p>
        @if (r.changes.length) {
          <table class="data">
            <thead><tr><th scope="col">Ticket or customer</th><th scope="col">Field</th><th scope="col">Now</th><th scope="col">With this version</th></tr></thead>
            <tbody>
              @for (ch of r.changes; track ch.runId) {
                @for (f of ch.fields; track f.field) {
                  <tr><td>{{ ch.subject }}</td><td class="condensed">{{ f.field }}</td><td>{{ show(f.before) }}</td><td>{{ show(f.after) }}</td></tr>
                }
              }
            </tbody>
          </table>
        }
      </section>
    }
  `,
  styles: `
    :host { display: grid; gap: var(--space-5); }
    header { display: grid; gap: var(--space-2); }
    .small { font-size: var(--text-sm); }
    .settings { display: grid; gap: var(--space-3); background: var(--sheet); padding: var(--space-5); border-radius: var(--radius-md); }
    .field { display: grid; grid-template-columns: minmax(0, 1fr) 9rem; gap: var(--space-1) var(--space-4); align-items: center; }
    .field label { font-size: var(--text-sm); }
    .field input[type='number'], .field input[type='text'] { border: 1px solid var(--rule-strong); border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2); }
    .field input[type='checkbox'] { justify-self: start; width: 18px; height: 18px; accent-color: var(--petrol); }
    .hint { grid-column: 1 / -1; font-size: var(--text-xs); }
    .note { grid-template-columns: 1fr; }
    .actions { display: flex; gap: var(--space-3); align-items: center; }
    .msg { font-size: var(--text-sm); color: var(--ink-2); max-width: 50ch; }
    td .btn { margin-left: var(--space-2); }
    .report { border-left: 3px solid var(--done); padding-left: var(--space-4); display: grid; gap: var(--space-3);
      animation: reveal var(--dur-base) var(--ease); }
    .report.fail { border-left-color: var(--failed); }
    @keyframes reveal { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    .cases { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
    .cases li.bad strong { color: var(--failed); }
  `,
})
export class AgentEditor {
  private readonly api = inject(Api);
  readonly agent = input.required<Agent>();
  readonly changedAgent = output<Agent>();
  readonly euros = euros;
  readonly when = when;
  readonly edits = signal<Record<string, number | boolean>>({});
  readonly note = signal('');
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly report = signal<TestReport | null>(null);

  readonly versionsNewestFirst = computed(() => [...this.agent().versions].sort((a, b) => b.version - a.version));
  readonly dirty = computed(() => this.agent().params.some((p) => this.changed(p)));

  value(p: Param): number | boolean {
    return this.edits()[p.name] ?? p.value;
  }

  num(p: Param): number {
    return Number(this.value(p));
  }

  bool(p: Param): boolean {
    const v = this.value(p);
    return v === true || v === 1;
  }

  changed(p: Param): boolean {
    const e = this.edits()[p.name];
    return e !== undefined && Number(e) !== Number(p.value);
  }

  set(p: Param, v: number | boolean): void {
    this.edits.update((x) => ({ ...x, [p.name]: v }));
  }

  /** The settings a version changes from the defaults, in words. */
  summary(config: Record<string, number | boolean>): string {
    return this.agent().params
      .filter((p) => config[p.name] !== undefined && Number(config[p.name]) !== Number(p.defaultValue))
      .map((p) => `${p.name} ${p.type === 'CENTS' ? euros(Number(config[p.name])) : String(config[p.name])}`)
      .join(', ');
  }

  show(v: unknown): string {
    return v === null || v === undefined ? 'nothing' : typeof v === 'object' ? JSON.stringify(v) : String(v);
  }

  private async act(f: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.message.set(null);
    try {
      await f();
    } catch (e) {
      this.message.set(e instanceof Error ? e.message : 'That did not work.');
    } finally {
      this.busy.set(false);
    }
  }

  saveDraft(): Promise<void> {
    return this.act(async () => {
      const config: Record<string, number | boolean> = {};
      for (const p of this.agent().params) {
        const v = this.value(p);
        config[p.name] = p.type === 'BOOL' ? (v === true || v === 1) : Number(v);
      }
      const a = await this.api.createVersion(this.agent().id, config, this.note());
      this.edits.set({});
      this.note.set('');
      this.message.set('Saved as a draft. Test it before publishing.');
      this.changedAgent.emit(a);
    });
  }

  test(v: number): Promise<void> {
    return this.act(async () => this.report.set(await this.api.test(this.agent().id, v)));
  }

  publish(v: number): Promise<void> {
    return this.act(async () => {
      const a = await this.api.publish(this.agent().id, v);
      this.message.set(`Version ${v} receives events now.`);
      this.changedAgent.emit(a);
    });
  }
}
