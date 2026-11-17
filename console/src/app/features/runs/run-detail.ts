import { Component, computed, inject, input, signal } from '@angular/core';
import { Api } from '../../core/api';
import { RunDetail as Detail } from '../../core/models';
import { agentName, eventName, when } from '../../shared/format';
import { StatusMark } from '../../shared/status-mark';
import { Trace } from './trace';

/** One run: what started it, every kept step with its result, the trace, and keeping it as a test case. */
@Component({
  selector: 'app-run-detail',
  imports: [StatusMark, Trace],
  template: `
    @let r = run();
    <header>
      <h2>{{ agentName[r.agentId] ?? r.agentId }}, {{ r.subject }}</h2>
      <dl class="facts condensed">
        <div><dt>Started by</dt><dd>{{ eventName[r.eventType] ?? r.eventType }} at {{ when(r.occurredAt) }}</dd></div>
        <div><dt>Status</dt><dd><app-status [status]="r.status" /></dd></div>
        <div><dt>Attempts</dt><dd class="num">{{ r.attempts }}</dd></div>
        <div><dt>Version</dt><dd class="num">{{ r.agentVersion }}</dd></div>
        <div><dt>Cost</dt><dd class="num">{{ cost() }}</dd></div>
      </dl>
      @if (r.error) {
        <p class="err">{{ r.error }}</p>
      }
    </header>

    <h3>Steps</h3>
    <ol class="steps">
      @for (s of r.steps; track s.name) {
        <li>
          <button type="button" class="step" [attr.aria-expanded]="openStep() === s.name"
            (click)="openStep.set(openStep() === s.name ? null : s.name)">
            <span class="seq num">{{ s.seq }}</span>
            <span class="sname">{{ stepName(s.name) }}</span>
            <span class="muted condensed">{{ when(s.at) }}</span>
          </button>
          @if (openStep() === s.name) {
            <pre>{{ pretty(s.json) }}</pre>
          }
        </li>
      } @empty {
        <li class="muted">No steps kept yet.</li>
      }
    </ol>

    <h3>Trace</h3>
    <app-trace [spans]="r.spans" />

    @if (r.status === 'DONE') {
      <div class="keep">
        <button class="btn" type="button" (click)="keep()" [disabled]="keeping()">Keep as a test case</button>
        @if (kept()) {
          <span role="status">{{ kept() }}</span>
        }
      </div>
    }
  `,
  styles: `
    :host { display: grid; gap: var(--space-4); }
    header { display: grid; gap: var(--space-3); }
    .facts { display: flex; flex-wrap: wrap; gap: var(--space-5); margin: 0; font-size: var(--text-sm); }
    .facts dt { color: var(--ink-2); }
    .facts dd { margin: 0; }
    .err { color: var(--failed); font-size: var(--text-sm); }
    .steps { list-style: none; padding: 0; margin: 0; display: grid; gap: 2px; }
    .step { all: unset; box-sizing: border-box; cursor: pointer; display: grid; grid-template-columns: 2.5em 1fr auto;
      width: 100%; padding: var(--space-2) var(--space-3); background: var(--sheet); border-radius: var(--radius-sm); }
    .step:hover { box-shadow: inset 3px 0 0 var(--petrol); }
    .step:focus-visible { outline: var(--focus-width) solid var(--petrol); }
    .seq { color: var(--ink-2); }
    pre { margin: 0; padding: var(--space-3); background: var(--sheet); border-left: 3px solid var(--petrol-wash);
      font-size: var(--text-xs); overflow-x: auto; max-height: 320px; white-space: pre-wrap; word-break: break-word; }
    .keep { display: flex; gap: var(--space-3); align-items: center; }
  `,
})
export class RunDetailView {
  private readonly api = inject(Api);
  readonly run = input.required<Detail>();
  readonly agentName = agentName;
  readonly eventName = eventName;
  readonly when = when;
  readonly openStep = signal<string | null>('decision');
  readonly keeping = signal(false);
  readonly kept = signal<string | null>(null);

  readonly cost = computed(() => {
    const micros = this.run().spans
      .filter((s) => s.name.startsWith('chat ') && !s.error)
      .reduce((a, s) => a + Number(s.attributes['msp.cost_micros'] ?? 0), 0);
    return micros === 0 ? 'no model call' : `€${(micros / 1e6).toFixed(5)}`;
  });

  stepName(n: string): string {
    if (n.startsWith('plan:')) return `would write ${n.slice(5)}`;
    return n;
  }

  pretty(json: string): string {
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }

  async keep(): Promise<void> {
    this.keeping.set(true);
    try {
      await this.api.addCase(this.run().agentId, this.run().id, `kept from run ${this.run().id}`);
      this.kept.set('Kept. Every new version of this agent must decide this case the same way.');
    } catch (e) {
      this.kept.set(e instanceof Error ? e.message : 'Could not keep it.');
    } finally {
      this.keeping.set(false);
    }
  }
}
