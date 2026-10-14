import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin, tap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { LogEntry, TelemetrySummary, TracePage } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { TrendChartComponent, TrendSeries } from '../../shared/trend-chart.component';

interface TelemetrySnapshot {
  summary: TelemetrySummary;
  traces: TracePage;
  logs: { items: LogEntry[]; dropped: number; capacity: number; note: string };
}

type TelemetryTab = 'metrics' | 'traces' | 'logs';

interface LatencySample {
  at: number;
  admission: number | null;
  completion: number | null;
  provider: number | null;
}

// Telemetry (mockup 07): Metrics/Traces/Logs tabs from summary + traces + the
// live log ring. Percentile charts have no server history source, so they
// accumulate one sample per successful refresh and say "since page open".
// Traces carry bounded IDs + timings only, no raw payloads; the log ring is
// newest-last, threshold-filtered client-side, and never holds stack traces.
@Component({
  selector: 'qd-telemetry',
  imports: [CommonModule, RouterLink, TimeAgoPipe, RefreshNoteComponent, StateMessageComponent, StatusBadgeComponent, TrendChartComponent],
  templateUrl: './telemetry.page.html',
})
export class TelemetryPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);

  protected readonly latency: LatencySample[] = [];
  protected tab: TelemetryTab = 'metrics';
  protected logLevel: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' = 'DEBUG';
  protected readonly logLevels = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const;
  protected readonly view$: Observable<PollResult<TelemetrySnapshot>> = this.polling
    .poll(() =>
      forkJoin({
        summary: this.api.getTelemetrySummary(),
        traces: this.api.listTraces(20),
        logs: this.api.listLogs(200),
      }),
    )
    .pipe(
      tap((r) => {
        if (r.status === 'ok') {
          // The backend reports p95 -1 with n=0 (no samples); a refresh with
          // nothing on either side is skipped, and a half-empty one records
          // null so the trend can mark it 0 with a caption, never -1.
          const s = r.value.summary;
          if (s.admission.n === 0 && s.completion.n === 0) return;
          this.latency.push({
            at: r.at,
            admission: s.admission.n === 0 ? null : s.admission.p95Ms,
            completion: s.completion.n === 0 ? null : s.completion.p95Ms,
            provider: s.riskProvider?.p95Ms ?? null,
          });
          if (this.latency.length > 60) this.latency.shift();
        }
      }),
    );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected admissionTrend(): TrendSeries[] {
    return [
      { label: 'HTTP admission', color: '#0369a1', values: this.latency.map((s) => s.admission ?? 0) },
      { label: 'End-to-end completion', color: '#0d9488', values: this.latency.map((s) => s.completion ?? 0) },
    ];
  }

  protected providerTrend(): TrendSeries[] {
    return [
      { label: 'Risk provider', color: '#7c3aed', values: this.latency.map((s) => s.provider ?? 0) },
    ];
  }

  protected hasProvider(samples: LatencySample[]): boolean {
    return samples.some((s) => s.provider !== null);
  }

  protected setTab(tab: TelemetryTab): void {
    this.tab = tab;
  }

  protected setLogLevel(level: string): void {
    if (level === 'DEBUG' || level === 'INFO' || level === 'WARN' || level === 'ERROR') {
      this.logLevel = level;
    }
  }

  private static levelRank(level: string): number {
    return ['DEBUG', 'INFO', 'WARN', 'ERROR'].indexOf(level);
  }

  protected visibleLogs(items: LogEntry[]): LogEntry[] {
    const threshold = TelemetryPage.levelRank(this.logLevel);
    return items.filter((e) => TelemetryPage.levelRank(e.level) >= threshold);
  }
}
