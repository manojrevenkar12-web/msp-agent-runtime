import { Component, inject, signal } from '@angular/core';
import { Api } from '../../core/api';
import { Approval } from '../../core/models';
import { euros, minutes, when } from '../../shared/format';
import { loadable } from '../../shared/loadable';
import { StateView } from '../../shared/state';

/** Invoices that wait for a person: every line, why it waits, and the decision, recorded with the person's name. */
@Component({
  selector: 'app-approvals',
  imports: [StateView],
  template: `
    <h1>Approvals</h1>
    <app-state what="The approvals" [loading]="approvals.loading()" [error]="approvals.error()" (retry)="approvals.reload()"
      [empty]="(approvals.value() ?? []).length === 0" emptyText="Nothing waits for a person. Invoices above the approval limit appear here.">
      <div class="list">
        @for (a of approvals.value(); track a.id) {
          <article [attr.aria-labelledby]="'h-' + a.id">
            <header>
              <h2 [id]="'h-' + a.id">Invoice {{ a.payload.customerId }}, {{ a.payload.month }}</h2>
              <span class="total num">{{ euros(total(a)) }}</span>
            </header>
            <p class="why">Waits because: {{ a.reason }}. Asked {{ when(a.createdAt) }}.</p>
            <details>
              <summary>{{ a.payload.lines.length }} lines</summary>
              <table class="data">
                <thead><tr><th scope="col">Line</th><th scope="col" class="r">Time</th><th scope="col" class="r">Amount</th></tr></thead>
                <tbody>
                  @for (l of a.payload.lines; track $index) {
                    <tr><td>{{ l.text }}</td><td class="r num">{{ l.minutes ? minutes(l.minutes) : '' }}</td>
                      <td class="r num">{{ l.kind === 'PREPAID_DRAW' ? 'from prepaid hours' : euros(l.amountCents) }}</td></tr>
                  }
                </tbody>
              </table>
            </details>
            <div class="actions">
              <button class="btn primary" type="button" (click)="decide(a, true)" [disabled]="busy() === a.id">Approve and send to the ERP</button>
              <button class="btn danger" type="button" (click)="decide(a, false)" [disabled]="busy() === a.id">Reject</button>
              @if (said()[a.id]) { <span role="status" class="muted">{{ said()[a.id] }}</span> }
            </div>
          </article>
        }
      </div>
    </app-state>
  `,
  styles: `
    :host { display: grid; gap: var(--space-5); }
    .list { display: grid; gap: var(--space-5); }
    article { background: var(--sheet); border-radius: var(--radius-md); padding: var(--space-5); display: grid; gap: var(--space-3); }
    header { display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-4); }
    .total { font-size: var(--text-xl); font-variation-settings: 'wdth' var(--wdth-wide); font-weight: var(--weight-heading); }
    .why { color: var(--ink-2); font-size: var(--text-sm); }
    summary { cursor: pointer; font-size: var(--text-sm); color: var(--petrol); }
    details table { margin-top: var(--space-3); }
    .actions { display: flex; gap: var(--space-3); align-items: center; flex-wrap: wrap; }
  `,
})
export class ApprovalsPage {
  private readonly api = inject(Api);
  readonly euros = euros;
  readonly minutes = minutes;
  readonly when = when;
  readonly approvals = loadable(() => this.api.approvals());
  readonly busy = signal<string | null>(null);
  readonly said = signal<Record<string, string>>({});

  total(a: Approval): number {
    return a.payload.lines.reduce((s, l) => s + l.amountCents, 0);
  }

  async decide(a: Approval, approve: boolean): Promise<void> {
    this.busy.set(a.id);
    try {
      await this.api.decide(a.id, approve);
      this.said.update((s) => ({ ...s, [a.id]: approve ? 'Approved.' : 'Rejected.' }));
      await this.approvals.reload();
    } catch (e) {
      this.said.update((s) => ({ ...s, [a.id]: e instanceof Error ? e.message : 'That did not work.' }));
    } finally {
      this.busy.set(null);
    }
  }
}
