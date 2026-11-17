import { Component, input, output } from '@angular/core';

/**
 * The three states every screen has before its content: loading (a skeleton the shape of what comes), failed (what
 * went wrong and a way to try again), and empty (what to do next).
 */
@Component({
  selector: 'app-state',
  template: `
    @if (loading()) {
      <div class="skeleton" role="status" [attr.aria-label]="'Loading ' + what()">
        @for (r of rows(); track $index) {
          <span [style.width.%]="r"></span>
        }
      </div>
    } @else if (error()) {
      <div class="error" role="alert">
        <p><strong>{{ what() }} could not be loaded.</strong> {{ error() }}</p>
        <button class="btn" type="button" (click)="retry.emit()">Load again</button>
      </div>
    } @else if (empty()) {
      <div class="empty">
        <p>{{ emptyText() }}</p>
      </div>
    } @else {
      <ng-content />
    }
  `,
  styles: `
    .skeleton { display: grid; gap: var(--space-3); padding: var(--space-4) 0; }
    .skeleton span { display: block; height: 14px; border-radius: var(--radius-sm);
      background: linear-gradient(90deg, var(--rule) 0%, var(--paper) 50%, var(--rule) 100%);
      background-size: 200% 100%; animation: shimmer 1.4s linear infinite; }
    @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
    .error { border-left: 3px solid var(--failed); padding: var(--space-3) var(--space-4); background: var(--sheet);
      display: grid; gap: var(--space-3); justify-items: start; }
    .empty { padding: var(--space-5) 0; color: var(--ink-2); }
  `,
})
export class StateView {
  readonly what = input.required<string>();
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyText = input('Nothing here yet.');
  readonly rows = input<number[]>([92, 78, 85, 60]);
  readonly retry = output<void>();
}
