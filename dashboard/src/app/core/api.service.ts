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

// Read-only API client. Every call is a GET against the lab backend (same origin in
// dev via proxy.conf.json); this service exposes no POST/PUT/DELETE surface at all.
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

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

  getInstruction(id: string): Observable<InstructionView> {
    return this.http.get<InstructionView>(`/api/v1/instructions/${encodeURIComponent(id)}`);
  }

  getRun(runId: string): Observable<RunSummary> {
    return this.http.get<RunSummary>(`/api/v1/runs/${encodeURIComponent(runId)}`);
  }

  getOverview(since?: string): Observable<LabSummary> {
    let params = new HttpParams();
    if (since) params = params.set('since', since);
    return this.http.get<LabSummary>('/api/v1/overview', { params });
  }

  getThroughput(since: string | undefined, bucketSeconds: number): Observable<ThroughputView> {
    let params = new HttpParams().set('bucketSeconds', String(bucketSeconds));
    if (since) params = params.set('since', since);
    return this.http.get<ThroughputView>('/api/v1/overview/throughput', { params });
  }

  getRecentPayments(limit = 5): Observable<RecentPayments> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<RecentPayments>('/api/v1/overview/recent', { params });
  }

  getQuarantine(): Observable<QuarantineView> {
    return this.http.get<QuarantineView>('/api/v1/quarantine');
  }

  getGuards(): Observable<Guards> {
    return this.http.get<Guards>('/api/v1/ops/guards');
  }

  getHealth(group: '' | 'liveness' | 'readiness' = ''): Observable<Health> {
    const path = group === '' ? '/actuator/health' : `/actuator/health/${group}`;
    return this.http.get<Health>(path);
  }

  getQueues(windowMinutes = 15): Observable<QueuesView> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<QueuesView>('/api/v1/ops/queues', { params });
  }

  getTelemetrySummary(windowMinutes = 15): Observable<TelemetrySummary> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<TelemetrySummary>('/api/v1/telemetry/summary', { params });
  }

  getLatencySeries(metric: string, windowMinutes = 15): Observable<LatencySeries> {
    const params = new HttpParams()
      .set('metric', metric)
      .set('windowMinutes', String(windowMinutes));
    return this.http.get<LatencySeries>('/api/v1/telemetry/latency-series', { params });
  }

  listTraces(limit = 20): Observable<TracePage> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<TracePage>('/api/v1/telemetry/traces', { params });
  }

  listLogs(limit = 200): Observable<LogList> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<LogList>('/api/v1/telemetry/logs', { params });
  }

  getTrace(traceId: string): Observable<TraceDetail> {
    return this.http.get<TraceDetail>(`/api/v1/telemetry/traces/${encodeURIComponent(traceId)}`);
  }

  listScenarios(): Observable<ScenarioCatalog> {
    return this.http.get<ScenarioCatalog>('/api/v1/scenarios');
  }

  listScenarioRuns(limit = 20, cursor?: string): Observable<ScenarioRunPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ScenarioRunPage>('/api/v1/scenarios/runs', { params });
  }

  getScenarioRun(runId: string): Observable<ScenarioRunReport> {
    return this.http.get<ScenarioRunReport>(`/api/v1/scenarios/runs/${encodeURIComponent(runId)}`);
  }

  getAudit(id: string): Observable<InstructionAudit> {
    return this.http.get<InstructionAudit>(`/api/v1/instructions/${encodeURIComponent(id)}/audit`);
  }

  getAttempts(id: string): Observable<InstructionAttempts> {
    return this.http.get<InstructionAttempts>(
      `/api/v1/instructions/${encodeURIComponent(id)}/attempts`,
    );
  }

  getRiskSummary(windowMinutes = 15): Observable<RiskSummary> {
    const params = new HttpParams().set('windowMinutes', String(windowMinutes));
    return this.http.get<RiskSummary>('/api/v1/risk/summary', { params });
  }

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

  getRiskAssessment(instructionId: string): Observable<RiskAssessmentDetail> {
    return this.http.get<RiskAssessmentDetail>(
      `/api/v1/risk/assessments/${encodeURIComponent(instructionId)}`,
    );
  }

  // One gauge/counter reading. A missing metric is "no reading" (null), never zero:
  // zero would masquerade as a measured value.
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
