import { Component, computed, input } from '@angular/core';
import { Span } from '../../core/models';
import { color, trace as t } from '../../theme/tokens';

/** A run's spans on one time axis: the attempt, its steps, model calls (with tokens) and writes. */
@Component({
  selector: 'app-trace',
  template: `
    @if (instant()) {
      <p class="muted note">These spans come from the simulation, where a run takes no time; a live runtime shows each
        span's duration here.</p>
    }
    <svg [attr.viewBox]="'0 0 ' + t.width + ' ' + rows().length * t.rowHeight" role="img"
      [attr.aria-label]="'Trace with ' + rows().length + ' spans'">
      @for (r of rows(); track r.span.spanId; let i = $index) {
        <g [attr.transform]="'translate(0,' + i * t.rowHeight + ')'">
          <text x="0" [attr.y]="t.rowHeight - 8" class="name" [class.child]="r.span.parentId">{{ r.span.name }}</text>
          <rect [attr.x]="t.labelWidth + r.x" y="5" [attr.width]="r.w" [attr.height]="t.rowHeight - 10"
            [attr.fill]="r.span.error ? c.failed : r.model ? c.petrol : r.span.parentId ? c.ruleStrong : c.ink2" rx="1">
            <title>{{ r.title }}</title>
          </rect>
          @if (r.model) {
            <text [attr.x]="t.labelWidth + r.x + r.w + 6" [attr.y]="t.rowHeight - 8" class="meta">{{ r.model }}</text>
          }
        </g>
      }
    </svg>
  `,
  styles: `
    svg { width: 100%; height: auto; display: block; }
    .name { font-size: 13px; fill: var(--ink); font-variation-settings: 'wdth' var(--wdth-narrow); }
    .name.child { fill: var(--ink-2); }
    .note { font-size: var(--text-sm); margin-bottom: var(--space-2); }
    .meta { font-size: 12px; fill: var(--ink-2); font-variation-settings: 'wdth' var(--wdth-narrow); }
  `,
})
export class Trace {
  readonly spans = input.required<Span[]>();
  readonly t = t;
  readonly c = color;

  readonly instant = computed(() => this.spans().length > 0 && this.spans().every((s) => s.start === s.end));

  readonly rows = computed(() => {
    const xs = [...this.spans()].sort((a, b) => Date.parse(a.start) - Date.parse(b.start) || (a.parentId ? 1 : -1));
    if (xs.length === 0) return [];
    const lo = Math.min(...xs.map((s) => Date.parse(s.start)));
    const hi = Math.max(...xs.map((s) => Date.parse(s.end)), lo + 1);
    const span = t.width - t.labelWidth - 130;
    return xs.map((s) => {
      const a = s.attributes;
      const tokens = a['gen_ai.usage.input_tokens'];
      const model = s.name.startsWith('chat ') && tokens
        ? `${tokens} in / ${a['gen_ai.usage.output_tokens']} out tokens`
        : null;
      return {
        span: s,
        x: ((Date.parse(s.start) - lo) / (hi - lo)) * span,
        w: Math.max(2, ((Date.parse(s.end) - Date.parse(s.start)) / (hi - lo)) * span),
        model,
        title: Object.entries(a).map(([k, v]) => `${k}: ${v}`).join('\n'),
      };
    });
  });
}
