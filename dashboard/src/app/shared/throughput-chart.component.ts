import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ThroughputPoint } from '../core/models';

// Overview throughput graph (mockup 01): durable server buckets rendered as an
// area chart with axis labels. Unlike qd-trend-chart (client-side samples since
// page open), these points are backend history — zero-filled across the window,
// so gaps are real zeros. Times are bucket starts, UTC; the page caption says so.
@Component({
  selector: 'qd-throughput-chart',
  imports: [CommonModule],
  template: `
    @if (points.length < 2) {
      <p
        class="rounded-md border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-700">
        Throughput needs at least two buckets — widen the window or wait for the next bucket.
      </p>
    } @else {
      <svg
        viewBox="0 0 600 240"
        preserveAspectRatio="none"
        class="h-60 w-full"
        role="img"
        [attr.aria-label]="ariaLabel">
        @for (t of ticks; track t.value) {
          <line x1="44" [attr.y1]="t.y" x2="592" [attr.y2]="t.y" stroke="#e2e8f0" stroke-width="1" />
          <text x="38" [attr.y]="t.y + 4" text-anchor="end" font-size="13" fill="#64748b">
            {{ t.value }}
          </text>
        }
        <polygon [attr.points]="areaPoints" fill="rgba(13, 148, 136, 0.15)" />
        <polyline
          [attr.points]="linePoints"
          fill="none"
          stroke="#0d9488"
          stroke-width="2"
          vector-effect="non-scaling-stroke" />
        @for (l of xLabels; track l.text) {
          <text
            [attr.x]="l.x"
            y="224"
            [attr.text-anchor]="l.anchor"
            font-size="13"
            fill="#64748b">
            {{ l.text }}
          </text>
        }
      </svg>
      <p class="mt-1 text-xs text-slate-600">
        Latest bucket: <span class="font-medium tabular-nums text-slate-800">{{ lastCount }}</span>
        completions · {{ points.length }} buckets
      </p>
    }
  `,
})
export class ThroughputChartComponent {
  @Input() points: ThroughputPoint[] = [];

  private readonly plotLeft = 44;
  private readonly plotRight = 592;
  private readonly plotTop = 8;
  private readonly plotBottom = 200;

  protected get maxCount(): number {
    return Math.max(0, ...this.points.map((p) => p.count));
  }

  protected get lastCount(): number {
    return this.points.length === 0 ? 0 : this.points[this.points.length - 1].count;
  }

  protected get ariaLabel(): string {
    return `Simulated completions per bucket, latest ${this.lastCount}, ${this.points.length} buckets`;
  }

  protected get ticks(): { value: number; y: number }[] {
    const max = this.maxCount;
    if (max === 0) return [{ value: 0, y: this.plotBottom }];
    const values = [0, Math.ceil(max / 2), max];
    return values.map((value) => ({ value, y: this.yFor(value, max) }));
  }

  protected get xLabels(): { text: string; x: number; anchor: string }[] {
    const n = this.points.length;
    const idx: { i: number; anchor: string }[] = [
      { i: 0, anchor: 'start' },
      { i: Math.floor((n - 1) / 2), anchor: 'middle' },
      { i: n - 1, anchor: 'end' },
    ];
    return idx.map(({ i, anchor }) => ({
      text: ThroughputChartComponent.hhmm(this.points[i].at),
      x: this.xFor(i),
      anchor,
    }));
  }

  protected get linePoints(): string {
    return this.points.map((p, i) => `${this.xFor(i).toFixed(1)},${this.yFor(p.count, this.scaleMax).toFixed(1)}`).join(' ');
  }

  protected get areaPoints(): string {
    const bottom = this.plotBottom;
    const line = this.points
      .map((p, i) => `${this.xFor(i).toFixed(1)},${this.yFor(p.count, this.scaleMax).toFixed(1)}`)
      .join(' ');
    return `${this.plotLeft},${bottom} ${line} ${this.plotRight},${bottom}`;
  }

  private get scaleMax(): number {
    return Math.max(1, this.maxCount);
  }

  private xFor(i: number): number {
    const n = this.points.length;
    const frac = n < 2 ? 0 : i / (n - 1);
    return this.plotLeft + frac * (this.plotRight - this.plotLeft);
  }

  private yFor(count: number, max: number): number {
    const frac = max <= 0 ? 0 : count / max;
    return this.plotBottom - frac * (this.plotBottom - this.plotTop);
  }

  private static hhmm(iso: string): string {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toISOString().slice(11, 16);
  }
}
