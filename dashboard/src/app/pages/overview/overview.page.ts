import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BehaviorSubject, Observable, catchError, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  InstructionPage,
  KNOWN_STATES,
  LabSummary,
  QueuesView,
  RecentPayments,
  RiskHeldPage,
  RiskSummary,
  RunSummary,
  TelemetrySummary,
  ThroughputView,
} from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { MinorCurrencyPipe } from '../../shared/minor-currency.pipe';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { TrendChartComponent, TrendSeries } from '../../shared/trend-chart.component';
import { ThroughputChartComponent } from '../../shared/throughput-chart.component';

const RECENT_KEY = 'qd.recentRuns';

interface AccountingSample {
  at: number;
  admitted: number;
  completed: number;
}

// Overview (UI-01, mockup 01): lab-wide KPI cards, the throughput graph, recent
// payments, the risk panel and the instruction path, then the run-scope
// accounting and safeguards below. Every figure names its scope (the window,
// the run id or "lab-wide") and its last refresh; unknown runs report zeros,
// never errors. Each stream below has exactly one template subscription — the
// polls are cold, so a second `| async` would double the request rate.
@Component({
  selector: 'qd-overview',
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MinorCurrencyPipe,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
    TrendChartComponent,
    ThroughputChartComponent,
  ],
  templateUrl: './overview.page.html',
})
export class OverviewPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);

  protected runInput = '';
  protected recentRuns: string[] = OverviewPage.loadRecent();
  protected readonly knownStates = KNOWN_STATES;

  private readonly runId$ = new BehaviorSubject<string>('');
  protected readonly activeRun$: Observable<string> = this.runId$.asObservable();

  // Lab-wide census window (mockup 01): minutes back from now, or 0 for all
  // time. Changing it restarts the lab, throughput and telemetry polls with a
  // fresh window edge; risk counts and queues are gauges, unaffected.
  protected readonly windowOptions = [
    { label: 'Last 15 minutes', minutes: 15 },
    { label: 'Last hour', minutes: 60 },
    { label: 'Last 24 hours', minutes: 1440 },
    { label: 'All time', minutes: 0 },
  ];
  protected windowMinutes = 15;
  private readonly window$ = new BehaviorSubject<number>(15);
  protected readonly lab$: Observable<PollResult<LabSummary>> = this.window$.pipe(
    switchMap((minutes) => {
      const since = minutes > 0 ? new Date(Date.now() - minutes * 60_000).toISOString() : undefined;
      return this.polling.poll(() => this.api.getOverview(since));
    }),
  );
  protected readonly throughput$: Observable<PollResult<ThroughputView>> = this.window$.pipe(
    switchMap((minutes) => {
      const since = minutes > 0 ? new Date(Date.now() - minutes * 60_000).toISOString() : undefined;
      return this.polling.poll(() =>
        this.api.getThroughput(since, OverviewPage.bucketFor(minutes)),
      );
    }),
  );
  protected readonly telemetry$: Observable<PollResult<TelemetrySummary>> = this.window$.pipe(
    switchMap((minutes) =>
      this.polling.poll(() =>
        this.api.getTelemetrySummary(OverviewPage.telemetryWindowFor(minutes)),
      ),
    ),
  );
  protected readonly queues$: Observable<PollResult<QueuesView>> = this.polling.poll(() =>
    this.api.getQueues(),
  );
  // Newest-first lab-wide recents plus the sampled trace id of the head row
  // (one detail read per refresh; null when unsampled or failed, never fatal).
  protected readonly recents$: Observable<{
    recents: PollResult<RecentPayments>;
    traceId: string | null;
  }> = this.polling
    .poll(() => this.api.getRecentPayments(5))
    .pipe(
      switchMap((recents) => {
        const first = recents.status === 'ok' ? recents.value.items[0] : undefined;
        if (!first) return of({ recents, traceId: null });
        return this.api.getInstruction(first.instructionId).pipe(
          map((detail) => ({ recents, traceId: detail.traceId ?? null })),
          catchError(() => of({ recents, traceId: null })),
        );
      }),
    );
  // Latest REVIEW assessment: the unfiltered list mixes ALLOW rows in, so the
  // panel must ask for REVIEW explicitly or it spotlights a passing row.
  protected readonly held$: Observable<PollResult<RiskHeldPage>> = this.polling.poll(() =>
    this.api.listRiskAssessments({ limit: 1, assessment: 'REVIEW' }),
  );

  protected readonly accounting: AccountingSample[] = [];
  protected readonly run$: Observable<PollResult<RunSummary>> = this.runId$.pipe(
    switchMap((runId) =>
      runId === ''
        ? new Observable<PollResult<RunSummary>>(() => {})
        : this.polling
            .poll(() => this.api.getRun(runId))
            .pipe(
              tap((r) => {
                if (r.status === 'ok') {
                  this.accounting.push({
                    at: r.at,
                    admitted: r.value.admitted,
                    completed: r.value.byState['COMPLETED_SIMULATED'] ?? 0,
                  });
                  if (this.accounting.length > 60) this.accounting.shift();
                }
              }),
            ),
    ),
  );
  protected readonly recent$: Observable<PollResult<InstructionPage>> = this.runId$.pipe(
    switchMap((runId) =>
      runId === ''
        ? new Observable<PollResult<InstructionPage>>(() => {})
        : this.polling.poll(() => this.api.listInstructions({ runId, limit: 8 })),
    ),
  );
  protected readonly ops$ = this.polling.poll(() =>
    forkJoin({
      quarantine: this.api.getQuarantine(),
      guards: this.api.getGuards(),
      readiness: this.api.getHealth('readiness'),
      liveness: this.api.getHealth('liveness'),
    }),
  );
  // Lab-wide risk counts ride their own poll: a backend without the B04 risk
  // endpoints must degrade this panel alone, never the safeguards above.
  protected readonly risk$: Observable<PollResult<RiskSummary>> = this.polling.poll(() =>
    this.api.getRiskSummary(),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected openRun(runId: string): void {
    const id = runId.trim();
    if (id === '') return;
    // Samples belong to the previous run's accounting; a new scope restarts
    // the series rather than splicing two runs into one line.
    this.accounting.splice(0, this.accounting.length);
    this.recentRuns = [id, ...this.recentRuns.filter((r) => r !== id)].slice(0, 8);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(this.recentRuns));
    } catch {
      // Private browsing: recency is a convenience, never a requirement.
    }
    this.runInput = id;
    this.runId$.next(id);
  }

  protected stateCount(run: RunSummary, state: string): number {
    return run.byState[state] ?? 0;
  }

  protected labCount(lab: LabSummary, state: string): number {
    return lab.byState[state] ?? 0;
  }

  protected setWindow(minutes: string): void {
    const parsed = Number(minutes);
    if (!Number.isInteger(parsed) || parsed < 0) return;
    this.windowMinutes = parsed;
    this.window$.next(parsed);
  }

  protected windowLabel(): string {
    return this.windowOptions.find((o) => o.minutes === this.windowMinutes)?.label ?? 'Custom';
  }

  // Bucket width follows the window so the graph holds ~15–150 points; the
  // caption names the unit, never implying per-minute for wider buckets.
  private static bucketFor(minutes: number): number {
    if (minutes <= 0) return 3600;
    if (minutes <= 15) return 60;
    if (minutes <= 60) return 120;
    return 600;
  }

  protected bucketUnit(): string {
    switch (OverviewPage.bucketFor(this.windowMinutes)) {
      case 60:
        return 'min';
      case 120:
        return '2-min buckets';
      case 600:
        return '10-min buckets';
      default:
        return 'hour buckets';
    }
  }

  // Telemetry windows are 1..60 server-side; wider overview windows read the
  // last hour and say so under the p95 card.
  private static telemetryWindowFor(minutes: number): number {
    if (minutes <= 0) return 60;
    return Math.min(Math.max(minutes, 1), 60);
  }

  protected telemetryWindowLabel(): string {
    return `last ${OverviewPage.telemetryWindowFor(this.windowMinutes)} min`;
  }

  // The backend reports p95 -1 with n=0 (no samples); the card shows a dash.
  protected completionP95(t: TelemetrySummary): number | null {
    return t.completion.n === 0 ? null : t.completion.p95Ms;
  }

  protected unresolved(run: RunSummary): number {
    return (
      this.stateCount(run, 'RECEIVED') +
      this.stateCount(run, 'BUSINESS_ACCEPTED') +
      this.stateCount(run, 'PENDING_CONFIRMATION') +
      this.stateCount(run, 'RISK_HELD')
    );
  }

  protected accountingTrend(): TrendSeries[] {
    return [
      { label: 'Admitted', color: '#0369a1', values: this.accounting.map((s) => s.admitted) },
      {
        label: 'Completed (sim)',
        color: '#0d9488',
        values: this.accounting.map((s) => s.completed),
      },
    ];
  }

  private static loadRecent(): string[] {
    try {
      const raw = localStorage.getItem(RECENT_KEY);
      const parsed: unknown = raw === null ? [] : JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter((r): r is string => typeof r === 'string') : [];
    } catch {
      return [];
    }
  }
}
