import { AfterViewInit, Component, ElementRef, computed, inject, input, output, viewChild } from '@angular/core';
import { animate, stagger } from 'motion';
import { RunSummary } from '../../core/models';
import { agentName, eventName, when } from '../../shared/format';
import { board, color, motion as m, statusColor, statusWord } from '../../theme/tokens';

interface Tick {
  run: RunSummary;
  x: number;
  colour: string;
  tall: boolean;
}

const LANES = ['triage', 'time-capture', 'concierge', 'billing'];

/**
 * One lane per agent across time; every run a tick in its status colour. Runs that needed a retry or a person stand
 * taller, so the exceptions are what the eye finds first.
 */
@Component({
  selector: 'app-switchboard',
  template: `
    <figure>
      <svg #svg [attr.viewBox]="'0 0 ' + b.width + ' ' + height()" role="img"
        [attr.aria-label]="'Runs by agent over time: ' + runs().length + ' runs'" preserveAspectRatio="xMinYMin meet">
        @for (lane of lanes(); track lane.id; let li = $index) {
          <g [attr.transform]="'translate(0,' + (li * (b.laneHeight + b.laneGap)) + ')'">
            <rect x="0" y="0" [attr.width]="b.width" [attr.height]="b.laneHeight" [attr.fill]="c.sheet" />
            <text x="12" [attr.y]="b.laneHeight / 2 + 5" class="lane-label">{{ lane.name }}</text>
            <text [attr.x]="b.labelWidth - 16" [attr.y]="b.laneHeight / 2 + 5" text-anchor="end" class="lane-count">{{ lane.ticks.length }}</text>
            <g class="ticks" [attr.data-lane]="li">
              @for (t of lane.ticks; track t.run.id) {
                <rect class="tick" [attr.x]="t.x" [attr.y]="(b.laneHeight - (t.tall ? b.tickHeight + 12 : b.tickHeight)) / 2"
                  [attr.width]="b.tickWidth" [attr.height]="t.tall ? b.tickHeight + 12 : b.tickHeight" [attr.fill]="t.colour"
                  tabindex="0" role="button"
                  [attr.aria-label]="label(t.run)" (click)="open.emit(t.run.id)" (keydown.enter)="open.emit(t.run.id)">
                  <title>{{ label(t.run) }}</title>
                </rect>
              }
            </g>
          </g>
        }
        <g [attr.transform]="'translate(0,' + (lanes().length * (b.laneHeight + b.laneGap)) + ')'">
          <text [attr.x]="b.labelWidth" y="16" class="axis">{{ from() }}</text>
          <text [attr.x]="b.width" y="16" text-anchor="end" class="axis">{{ to() }}</text>
        </g>
      </svg>
      <figcaption class="legend condensed">
        @for (s of legend; track s.status) {
          <span><i [style.background]="s.colour" [class.tall]="s.tall"></i>{{ s.word }}</span>
        }
      </figcaption>
    </figure>
  `,
  styles: `
    figure { margin: 0; }
    svg { width: 100%; height: auto; display: block; }
    .lane-label { font-size: 15px; font-variation-settings: 'wdth' 100; font-weight: 560; fill: var(--ink); }
    .lane-count { font-size: 13px; font-variation-settings: 'wdth' var(--wdth-narrow); fill: var(--ink-2); }
    .axis { font-size: 12px; fill: var(--ink-2); font-variation-settings: 'wdth' var(--wdth-narrow); }
    .tick { cursor: pointer; transform-box: fill-box; transform-origin: center; }
    .tick:hover, .tick:focus-visible { fill: var(--ink); outline: none; }
    .legend { display: flex; gap: var(--space-5); font-size: var(--text-sm); color: var(--ink-2); margin-top: var(--space-3); flex-wrap: wrap; }
    .legend i { display: inline-block; width: 3px; height: 12px; margin-right: 6px; vertical-align: -1px; }
    .legend i.tall { height: 16px; vertical-align: -3px; }
  `,
})
export class Switchboard implements AfterViewInit {
  readonly runs = input.required<RunSummary[]>();
  readonly open = output<string>();
  readonly b = board;
  readonly c = color;
  private readonly svg = viewChild.required<ElementRef<SVGSVGElement>>('svg');
  private readonly host = inject(ElementRef);

  readonly legend = [
    { status: 'DONE', word: statusWord['DONE'], colour: statusColor['DONE'], tall: false },
    { status: 'RETRY', word: 'needed another attempt', colour: statusColor['RETRY'], tall: true },
    { status: 'WAITING_APPROVAL', word: statusWord['WAITING_APPROVAL'], colour: statusColor['WAITING_APPROVAL'], tall: true },
    { status: 'FAILED', word: 'failed or gave up', colour: statusColor['FAILED'], tall: true },
  ];

  private readonly domain = computed(() => {
    const xs = this.runs().map((r) => Date.parse(r.createdAt));
    const lo = Math.min(...xs);
    const hi = Math.max(...xs);
    return { lo, hi: hi === lo ? lo + 1 : hi };
  });

  readonly lanes = computed(() => {
    const { lo, hi } = this.domain();
    const span = this.b.width - this.b.labelWidth - this.b.tickWidth;
    return LANES.map((id) => ({
      id,
      name: agentName[id] ?? id,
      ticks: this.runs()
        .filter((r) => r.agentId === id)
        .map<Tick>((r) => ({
          run: r,
          x: this.b.labelWidth + ((Date.parse(r.createdAt) - lo) / (hi - lo)) * span,
          colour: r.attempts > 1 && r.status === 'DONE' ? color.retry : (statusColor[r.status] ?? color.ink2),
          tall: r.status !== 'DONE' || r.attempts > 1,
        })),
    }));
  });

  readonly height = computed(() => this.lanes().length * (this.b.laneHeight + this.b.laneGap) + this.b.axisHeight);
  readonly from = computed(() => (this.runs().length ? when(new Date(this.domain().lo).toISOString()) : ''));
  readonly to = computed(() => (this.runs().length ? when(new Date(this.domain().hi).toISOString()) : ''));

  label(r: RunSummary): string {
    const retried = r.attempts > 1 ? `, ${r.attempts} attempts` : '';
    return `${agentName[r.agentId] ?? r.agentId}: ${eventName[r.eventType] ?? r.eventType} ${r.subject}, ${statusWord[r.status] ?? r.status}${retried}`;
  }

  ngAfterViewInit(): void {
    const reduce = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce || typeof Element.prototype.animate !== 'function') return;
    const groups = Array.from(this.svg().nativeElement.querySelectorAll<SVGGElement>('g.ticks'));
    groups.forEach((g, i) => {
      const ticks = g.querySelectorAll('rect.tick');
      if (ticks.length === 0) return;
      animate(ticks, { scaleY: [0, 1], opacity: [0, 1] },
        { duration: m.base, ease: m.ease, delay: stagger(m.tickStagger, { startDelay: i * m.laneStagger }) });
    });
    void this.host;
  }
}
