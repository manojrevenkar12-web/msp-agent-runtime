import { Component, computed, input } from '@angular/core';
import { Evaluation } from '../../core/models';
import { count, euros, pct } from '../../shared/format';

/** The evaluation's month, three ways: the status quo, a model acting on its own answers, and this runtime. */
@Component({
  selector: 'app-compare',
  template: `
    <table class="data">
      <caption class="visually-hidden">The same simulated months under three designs</caption>
      <thead>
        <tr>
          <th scope="col">Per month</th>
          <th scope="col" class="r">Dispatcher and typed time</th>
          <th scope="col" class="r">Model decides</th>
          <th scope="col" class="r us">This runtime</th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.label) {
          <tr>
            <th scope="row">{{ row.label }}</th>
            @for (cell of row.cells; track $index; let last = $last) {
              <td class="r num" [class.us]="last">{{ cell }}</td>
            }
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    th[scope='row'] { font-weight: 400; color: var(--ink); font-variation-settings: 'wdth' 100; border-bottom: 1px solid var(--rule); }
    .us { background: var(--petrol-wash); font-weight: var(--weight-medium); }
    thead .us { color: var(--petrol); }
  `,
})
export class Compare {
  readonly evaluation = input.required<Evaluation>();

  readonly rows = computed(() => {
    const e = this.evaluation();
    const ds = [e.manual.score, e.modelDecides.score, e.platform.score];
    const row = (label: string, f: (s: Record<string, number>) => string) => ({ label, cells: ds.map(f) });
    return [
      row('Worked time not billed', (s) => `${euros(s['unbilledCents'])} (${pct(s['unbilledCents'], s['trueRevenueCents'])})`),
      row('Billed for time not worked', (s) => euros(s['overbilledCents'])),
      row('Work sessions entered twice', (s) => count(s['sessionsEnteredTwice'])),
      row('Urgent tickets past first-response SLA', (s) => `${count(s['urgentBreaches'])} of ${count(s['urgentTickets'])}`),
      row('Customer demands that made a ticket P1', (s) => `${count(s['injectionsThatRaisedPriority'])} of ${count(s['injections'])}`),
      row('Customer messages repeating internal notes', (s) => count(s['updatesLeakingInternalNotes'])),
      row('Writes made twice', (s) => count(s['duplicateInvoices'] + s['duplicateUpdates'] + s['duplicateTriageWrites'])),
      row('Office minutes', (s) => count(s['adminMinutes'])),
    ];
  });
}
