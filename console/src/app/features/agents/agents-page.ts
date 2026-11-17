import { Component, computed, inject, signal } from '@angular/core';
import { Api } from '../../core/api';
import { Agent } from '../../core/models';
import { loadable } from '../../shared/loadable';
import { StateView } from '../../shared/state';
import { AgentEditor } from './agent-editor';

@Component({
  selector: 'app-agents',
  imports: [StateView, AgentEditor],
  template: `
    <h1>Agents</h1>
    <p class="muted lede">
      A model reads tickets and notes and drafts messages; the agents' code decides priorities, minutes and amounts.
      Settings stay within the limits each agent was tested for, and a new version receives events only after it
      decided every test case as expected.
    </p>
    <app-state what="The agents" [loading]="agents.loading()" [error]="agents.error()" (retry)="agents.reload()"
      [empty]="list().length === 0" emptyText="No agents are installed.">
      <div class="layout">
        <ul class="list" role="listbox" aria-label="Agents">
          @for (a of list(); track a.id) {
            <li role="option" [attr.aria-selected]="a.id === current()?.id" tabindex="0"
              (click)="pick.set(a.id)" (keydown.enter)="pick.set(a.id)" [class.on]="a.id === current()?.id">
              <span class="t">{{ a.title }}</span>
              <span class="condensed muted">version {{ a.published ?? '–' }}{{ drafts(a) ? ', ' + drafts(a) + ' draft' : '' }}</span>
            </li>
          }
        </ul>
        @if (current(); as a) {
          <app-agent-editor [agent]="a" (changedAgent)="replace($event)" />
        }
      </div>
    </app-state>
  `,
  styles: `
    :host { display: grid; gap: var(--space-4); }
    .lede { margin-bottom: var(--space-4); }
    .layout { display: grid; grid-template-columns: 15rem minmax(0, 1fr); gap: var(--space-7); align-items: start; }
    .list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-1); position: sticky; top: var(--space-5); }
    .list li { display: grid; padding: var(--space-3); border-radius: var(--radius-sm); cursor: pointer; }
    .list li:hover { background: var(--sheet); }
    .list li.on { background: var(--sheet); box-shadow: inset 3px 0 0 var(--petrol); }
    .t { font-weight: var(--weight-medium); }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } .list { position: static; } }
  `,
})
export class AgentsPage {
  private readonly api = inject(Api);
  readonly agents = loadable(() => this.api.agents());
  private readonly overrides = signal<Record<string, Agent>>({});
  readonly pick = signal<string | null>(null);
  readonly list = computed(() => (this.agents.value() ?? []).map((a) => this.overrides()[a.id] ?? a));
  readonly current = computed(() => this.list().find((a) => a.id === this.pick()) ?? this.list()[0] ?? null);

  drafts(a: Agent): number {
    return a.versions.filter((v) => v.status === 'DRAFT').length;
  }

  replace(a: Agent): void {
    this.overrides.update((o) => ({ ...o, [a.id]: a }));
  }
}
