import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import {
  Guards,
  Health,
  InstructionAudit,
  InstructionAttempts,
  InstructionPage,
  InstructionView,
  LabSummary,
  LatencySeries,
  ListFilters,
  LogList,
  MetricValue,
  QuarantineView,
  QueuesView,
  RecentPayments,
  RiskAssessmentDetail,
  RiskFilters,
  RiskHeldPage,
  RiskSummary,
  RunSummary,
  ScenarioCatalog,
  ScenarioRunPage,
  ScenarioRunReport,
  TelemetrySummary,
  ThroughputView,
  TraceDetail,
  TracePage,
} from './models';

/**
 * Read-only API client for the lab backend.
 *
 * Every call is a GET (same origin in dev via proxy.conf.json); this
 * service deliberately exposes no POST, PUT, or DELETE surface, matching
 * the dashboard's observe-only contract. Response shapes come from
 * `models.ts` and mirror the backend records exactly. Paged reads
 * surface the `X-Next-Cursor` header as an opaque token.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  /** One cursor page of instructions; `nextCursor` comes from the response header, null on the last page. */
  listInstructions(filters: ListFilters): Observable<InstructionPage> {
    let params = new HttpParams().set('limit', String(filters.limit));
    if (filters.runId) params = params.set('runId', filters.runId);
    if (filters.status) params = params.set('status', filters.status);
    if (filters.since) params = params.set('since', filters.since);
    if (filters.until) params = params.set('until', filters.until);
    if (filters.cursor) params = params.set('cursor', filters.cursor);
    if (filters.order) params = params.set('order', filters.order);
    return this.http
      .get<InstructionView[]>('/api/v1/instructions', { observe: 'response', params })
      .pipe(
        map((response: HttpResponse<InstructionView[]>) => ({
          items: response.body ?? [],
          nextCursor: response.headers.get('X-Next-Cursor'),
        })),
      );
  }

  /** One instruction by ID (404 for unknown IDs). Detail views also carry the trace link ID. */
  getInstruction(id: string): Observable<InstructionView> {
    return this.http.get<InstructionView>(`/api/v1/instructions/${encodeURIComponent(id)}`);
  }

  /** Run accounting by state plus outbox/reconciliation gauges. Unknown runs report zeros. */
  getRun(runId: string): Observable<RunSummary> {
    return this.http.get<RunSummary>(`/api/v1/runs/${encodeURIComponent(runId)}`);
  }

  /** Lab-wide census, optionally windowed from `since` (ISO instant). */
  getOverview(since?: string): Observable<LabSummary> {
    let params = new HttpParams();
    if (since) params = params.set('since', since);
    return this.http.get<LabSummary>('/api/v1/overview', { params });
  }

  /** Durable per-bucket completion counts, zero-filled across the window for charting. */
  getThroughput(since: string | undefined, bucketSeconds: number): Observable<ThroughputView> {
    let params = new HttpParams().set('bucketSeconds', String(bucketSeconds));
    if (since) params = params.set('since', since);
    return this.http.get<ThroughputView>('/api/v1/overview/throughput', { params });
  }

  /** The newest payments lab-wide (overview table). First page only, no cursor. */
  getRecentPayments(limit = 5): Observable<RecentPayments> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<RecentPayments>('/api/v1/overview/recent', { params });
  }

  /** Quarantine projection with its freshness heartbeat (`stale` when behind). */
  getQuarantine(): Observable<QuarantineView> {
    return this.http.get<QuarantineView>('/api/v1/quarantine');
  }

  /** Laboratory safeguard readings (backlog and disk halts). */
  getGuards(): Observable<Guards> {
    return this.http.get<Guards>('/api/v1/ops/guards');
  }

  /** Actuator health, optionally one probe group (`liveness` or `readiness`). */
  getHealth(group: '' | 'liveness' | 'readiness' = ''): Observable<Health> {
    const path = group === '' ? '/actuator/health' : `/actuator/health/${group}`;
    return this.http.get<Health>(path);
  }

  /** Combined queues snapshot: outbox, MQ estimate, quarantine freshness, dedup count. */
  getQueues(windowMinutes = 15): Observable<QueuesView> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<QueuesView>('/api/v1/ops/queues', { params });
  }

  /** Latency cards, sampling state, and exporter health for the window. */
  getTelemetrySummary(windowMinutes = 15): Observable<TelemetrySummary> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<TelemetrySummary>('/api/v1/telemetry/summary', { params });
  }

  /** Per-minute p95 series for one metric (`admission`, `completion`, or `riskProvider`). */
  getLatencySeries(metric: string, windowMinutes = 15): Observable<LatencySeries> {
    const params = new HttpParams()
      .set('metric', metric)
      .set('windowMinutes', String(windowMinutes));
    return this.http.get<LatencySeries>('/api/v1/telemetry/latency-series', { params });
  }

  /** Newest sampled traces, one row per trace. */
  listTraces(limit = 20): Observable<TracePage> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<TracePage>('/api/v1/telemetry/traces', { params });
  }

  /** Tail of the backend process's live log ring (per-process, clears on restart). */
  listLogs(limit = 200): Observable<LogList> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<LogList>('/api/v1/telemetry/logs', { params });
  }

  /** One trace waterfall (404 when unsampled or evicted from the ring). */
  getTrace(traceId: string): Observable<TraceDetail> {
    return this.http.get<TraceDetail>(`/api/v1/telemetry/traces/${encodeURIComponent(traceId)}`);
  }

  /** The static scenario catalog with real CLI command previews. */
  listScenarios(): Observable<ScenarioCatalog> {
    return this.http.get<ScenarioCatalog>('/api/v1/scenarios');
  }

  /** Runs discovered from stored instructions, newest first, offset-paged. */
  listScenarioRuns(limit = 20, cursor?: string): Observable<ScenarioRunPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ScenarioRunPage>('/api/v1/scenarios/runs', { params });
  }

  /** Server-derived evidence for one run (unknown runs report zeros). */
  getScenarioRun(runId: string): Observable<ScenarioRunReport> {
    return this.http.get<ScenarioRunReport>(`/api/v1/scenarios/runs/${encodeURIComponent(runId)}`);
  }

  /** Durable business-transition audit trail for one instruction. */
  getAudit(id: string): Observable<InstructionAudit> {
    return this.http.get<InstructionAudit>(`/api/v1/instructions/${encodeURIComponent(id)}/audit`);
  }

  /** Worker attempt evidence (telemetry, never business truth) for one instruction. */
  getAttempts(id: string): Observable<InstructionAttempts> {
    return this.http.get<InstructionAttempts>(
      `/api/v1/instructions/${encodeURIComponent(id)}/attempts`,
    );
  }

  /** Risk hold counters for the current gate mode plus the Jev usage ledger. */
  getRiskSummary(windowMinutes = 15): Observable<RiskSummary> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<RiskSummary>('/api/v1/risk/summary', { params });
  }

  /** One cursor page of assessed payments, newest first; filters map 1:1 to query params. */
  listRiskAssessments(filters: RiskFilters): Observable<RiskHeldPage> {
    let params = new HttpParams().set('limit', String(filters.limit));
    if (filters.assessment) params = params.set('assessment', filters.assessment);
    if (filters.reason) params = params.set('reason', filters.reason);
    if (filters.q) params = params.set('q', filters.q);
    if (filters.cursor) params = params.set('cursor', filters.cursor);
    return this.http
      .get<{ items: RiskHeldPage['items']; mode: string }>('/api/v1/risk/assessments', {
        observe: 'response',
        params,
      })
      .pipe(
        map((response: HttpResponse<{ items: RiskHeldPage['items']; mode: string }>) => ({
          items: response.body?.items ?? [],
          nextCursor: response.headers.get('X-Next-Cursor'),
          mode: response.body?.mode ?? '',
        })),
      );
  }

  /** Full latest assessment for one instruction (404 when never assessed). */
  getRiskAssessment(instructionId: string): Observable<RiskAssessmentDetail> {
    return this.http.get<RiskAssessmentDetail>(
      `/api/v1/risk/assessments/${encodeURIComponent(instructionId)}`,
    );
  }

  /**
   * One actuator gauge/counter reading. A missing metric resolves to
   * `null` ("no reading"), never zero: zero would masquerade as a measured
   * value. Failures also resolve to null so one dead series cannot blank
   * the whole metrics table.
   */
  getMetric(name: string, tag?: { key: string; value: string }): Observable<MetricValue> {
    let params = new HttpParams();
    if (tag) params = params.set('tag', `${tag.key}:${tag.value}`);
    return this.http
      .get<{ measurements?: Array<{ value?: number }> }>(
        `/actuator/metrics/${encodeURIComponent(name)}`,
        { params },
      )
      .pipe(
        map((body) => ({ name, value: body.measurements?.[0]?.value ?? null })),
        catchError(() => of({ name, value: null })),
      );
  }
}
