import { Component, computed, input } from '@angular/core';
import { statusColor, statusWord } from '../theme/tokens';

/** A run's status as a shape and a word: a bar for done, a ring for waiting, a cross for failed. */
@Component({
  selector: 'app-status',
  template: `
    <span class="mark" [style.color]="colour()">
      <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
        @switch (shape()) {
          @case ('ring') { <circle cx="5" cy="5" r="3.5" fill="none" stroke="currentColor" stroke-width="2" /> }
          @case ('cross') { <path d="M2 2 L8 8 M8 2 L2 8" stroke="currentColor" stroke-width="2" /> }
          @case ('half') { <path d="M5 1 A4 4 0 0 1 5 9 Z" fill="currentColor" /><circle cx="5" cy="5" r="4" fill="none" stroke="currentColor" stroke-width="1.2" /> }
          @default { <rect x="1" y="1" width="8" height="8" rx="1" fill="currentColor" /> }
        }
      </svg>
      <span class="word">{{ word() }}</span>
    </span>
  `,
  styles: `
    .mark { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
    .word { color: var(--ink); font-variation-settings: 'wdth' var(--wdth-narrow); }
  `,
})
export class StatusMark {
  readonly status = input.required<string>();
  readonly colour = computed(() => statusColor[this.status()] ?? 'currentColor');
  readonly word = computed(() => statusWord[this.status()] ?? this.status().toLowerCase());
  readonly shape = computed(() => {
    switch (this.status()) {
      case 'WAITING_APPROVAL':
        return 'ring';
      case 'FAILED':
      case 'DEAD':
        return 'cross';
      case 'RETRY':
      case 'RUNNING':
      case 'PENDING':
        return 'half';
      default:
        return 'bar';
    }
  });
}
