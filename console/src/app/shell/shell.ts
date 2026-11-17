import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from '../core/api';
import { Session } from '../core/session';

/** The rail: where you are, the four places, and whether this console shows a live runtime or the simulated month. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  template: `
    <div class="frame">
      <nav class="rail" aria-label="Main">
        <div class="brand">
          <span class="name">Agent runtime</span>
          <span class="tenant condensed">{{ api.demo() ? 'Simulated month' : 'Connected runtime' }}</span>
        </div>
        <ul>
          <li><a routerLink="/" routerLinkActive="on" [routerLinkActiveOptions]="{ exact: true }">Overview</a></li>
          <li><a routerLink="/runs" routerLinkActive="on">Runs</a></li>
          <li><a routerLink="/agents" routerLinkActive="on">Agents</a></li>
          <li><a routerLink="/approvals" routerLinkActive="on">Approvals</a></li>
        </ul>
        <div class="connect">
          @if (api.demo()) {
            <form (ngSubmit)="connect()">
              <label for="key" class="condensed muted">API key of a runtime</label>
              <input id="key" name="key" type="password" autocomplete="off" [(ngModel)]="key" />
              <button class="btn" type="submit" [disabled]="!key().trim()">Connect</button>
            </form>
          } @else {
            <button class="btn" type="button" (click)="session.disconnect()">Show the simulated month</button>
          }
        </div>
      </nav>
      <main>
        @if (api.demo()) {
          <p class="banner" role="note">
            You are looking at a simulated month at a service provider, recorded by the evaluation. Nothing you do here
            reaches a real system.
          </p>
        }
        <router-outlet />
      </main>
    </div>
  `,
  styles: `
    .frame { display: grid; grid-template-columns: var(--rail-width) 1fr; min-height: 100vh; }
    .rail { border-right: 1px solid var(--rule-strong); padding: var(--space-5) var(--space-4); display: flex;
      flex-direction: column; gap: var(--space-6); position: sticky; top: 0; height: 100vh; background: var(--paper); }
    .brand { display: grid; gap: 2px; }
    .name { font-variation-settings: 'wdth' var(--wdth-wide); font-weight: var(--weight-heading); font-size: var(--text-lg); }
    .tenant { color: var(--ink-2); font-size: var(--text-sm); }
    ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-1); }
    a { display: block; padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm); color: var(--ink);
      text-decoration: none; }
    a:hover { background: var(--sheet); }
    a.on { background: var(--sheet); box-shadow: inset 3px 0 0 var(--petrol); font-weight: var(--weight-medium); }
    .connect { margin-top: auto; }
    form { display: grid; gap: var(--space-2); }
    label { font-size: var(--text-sm); }
    input { border: 1px solid var(--rule-strong); border-radius: var(--radius-sm); padding: var(--space-2); background: var(--sheet); width: 100%; min-width: 0; }
    main { padding: var(--space-6) var(--space-7); max-width: calc(var(--content-max) + 2 * var(--space-7)); width: 100%; }
    .banner { font-size: var(--text-sm); color: var(--ink-2); border-left: 3px solid var(--petrol);
      padding: var(--space-1) var(--space-3); margin-bottom: var(--space-5); max-width: none; }
    @media (max-width: 860px) {
      .frame { grid-template-columns: 1fr; }
      .rail { position: static; height: auto; flex-direction: row; flex-wrap: wrap; align-items: center; gap: var(--space-4); }
      ul { grid-auto-flow: column; }
      .connect { margin-top: 0; }
      main { padding: var(--space-5) var(--space-4); }
    }
  `,
})
export class Shell {
  readonly api = inject(Api);
  readonly session = inject(Session);
  readonly key = signal('');

  connect(): void {
    this.session.connect(this.key().trim());
    this.key.set('');
  }
}
