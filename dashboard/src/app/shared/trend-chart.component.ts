import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface TrendSeries {
  label: string;
  color: string;
  values: number[];
}

// Minimal client-side trend chart. The backend keeps no latency/backlog history,
// so pages accumulate one sample per successful refresh and pass the raw values
// here; the caption naming that source stays in the page template, next to the
// chart, so it can never drift from the data it describes.
@Component({
  selector: 'qd-trend-chart',
  imports: [CommonModule],
  template: `
    @if (pointCount < 2) {
      <p class="rounded-md border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-700">
        Collecting samples — one point per refresh{{ pointCount === 1 ? ' (1 so far)' : '' }}.
      </p>
    } @else {
      <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="h-32 w-full" role="img" [attr.aria-label]="ariaLabel">
        @for (s of series; track s.label) {
          <polyline
            [attr.points]="points(s.values)"
            fill="none"
            [attr.stroke]="s.color"
            stroke-width="1"
            vector-effect="non-scaling-stroke" />
        }
      </svg>
      <ul class="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600" aria-label="Series">
        @for (s of series; track s.label) {
          <li class="inline-flex items-center gap-1.5">
            <span aria-hidden="true" class="inline-block h-2 w-2 rounded-full" [style.background-color]="s.color"></span>
            {{ s.label }}: <span class="font-medium tabular-nums text-slate-800">{{ lastOf(s.values) }}</span>
          </li>
        }
      </ul>
    }
  `,
})
export class TrendChartComponent {
  @Input() series: TrendSeries[] = [];

  protected get pointCount(): number {
    return Math.max(0, ...this.series.map((s) => s.values.length));
  }

  protected get ariaLabel(): string {
    return this.series.map((s) => `${s.label}, latest ${this.lastOf(s.values)}`).join('; ');
  }

  protected lastOf(values: number[]): number {
    return values.length === 0 ? 0 : values[values.length - 1];
  }

  protected points(values: number[]): string {
    const max = Math.max(1, ...this.series.flatMap((s) => s.values));
    const n = values.length;
    return values
      .map((v, i) => `${((i / Math.max(1, n - 1)) * 100).toFixed(1)},${(38 - (v / max) * 36).toFixed(1)}`)
      .join(' ');
  }
}
