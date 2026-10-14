/**
 * ApiService contract tests against HttpTestingController: filters become
 * the right query params (unset ones omitted), the next-page cursor is read
 * from the X-Next-Cursor header, and every endpoint group (instructions,
 * runs, queues, telemetry, scenarios, risk) hits its documented path.
 */
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let service: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists instructions with filters and reads the next cursor header', () => {
    const body = [{ instructionId: 'id-1' }];
    service
      .listInstructions({
        runId: 'run-1',
        status: 'RECEIVED',
        since: '2026-10-01T00:00:00Z',
        limit: 10,
      })
      .subscribe((page) => {
        expect(page.items).toEqual(body);
        expect(page.nextCursor).toBe('cursor-1');
      });
    const req = http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/instructions' &&
        r.params.get('runId') === 'run-1' &&
        r.params.get('status') === 'RECEIVED' &&
        r.params.get('since') === '2026-10-01T00:00:00Z' &&
        r.params.get('limit') === '10',
    );
    req.flush(body, { headers: { 'X-Next-Cursor': 'cursor-1' } });
  });

  it('omits unset filters and tolerates a missing cursor header', () => {
    service.listInstructions({ limit: 50 }).subscribe((page) => {
      expect(page.items).toEqual([]);
      expect(page.nextCursor).toBeNull();
    });
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/instructions');
    expect(req.request.params.get('runId')).toBeNull();
    expect(req.request.params.get('status')).toBeNull();
    req.flush([]);
  });

  it('reads run, quarantine, guards and health endpoints', () => {
    service.getRun('run-1').subscribe((run) => expect(run.runId).toBe('run-1'));
    http.expectOne('/api/v1/runs/run-1').flush({ runId: 'run-1' });
    service.getQuarantine().subscribe((q) => expect(q.stale).toBe(false));
    http.expectOne('/api/v1/quarantine').flush({ stale: false, items: [] });
    service.getGuards().subscribe((g) => expect(g.haltTraffic).toBe(false));
    http.expectOne('/api/v1/ops/guards').flush({ haltTraffic: false });
    service.getHealth('readiness').subscribe((h) => expect(h.status).toBe('UP'));
    http.expectOne('/actuator/health/readiness').flush({ status: 'UP' });
  });

  it('reads queues, telemetry, traces and scenario endpoints', () => {
    service.getQueues().subscribe((q) => expect(q.dedupLast15Min).toBe(5));
    const queues = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/ops/queues');
    expect(queues.request.params.get('windowMinutes')).toBe('15');
    queues.flush({ dedupLast15Min: 5 });
    service.getTelemetrySummary().subscribe((s) => expect(s.windowMinutes).toBe(15));
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/api/v1/telemetry/summary')
      .flush({ windowMinutes: 15 });
    service.getLatencySeries('admission').subscribe((s) => expect(s.metric).toBe('admission'));
    const series = http.expectOne(
      (r) => r.method === 'GET' && r.url === '/api/v1/telemetry/latency-series',
    );
    expect(series.request.params.get('metric')).toBe('admission');
    series.flush({ metric: 'admission', bucketSeconds: 60, points: [] });
    service.listTraces(5).subscribe((p) => expect(p.items).toEqual([]));
    const traces = http.expectOne(
      (r) => r.method === 'GET' && r.url === '/api/v1/telemetry/traces',
    );
    expect(traces.request.params.get('limit')).toBe('5');
    traces.flush({ items: [], nextCursor: null });
    service.getTrace('trc-1').subscribe((d) => expect(d.traceId).toBe('trc-1'));
    http.expectOne('/api/v1/telemetry/traces/trc-1').flush({ traceId: 'trc-1' });
    service.listScenarios().subscribe((c) => expect(c.items).toEqual([]));
    http.expectOne('/api/v1/scenarios').flush({ items: [] });
    service.listScenarioRuns(20, 'c1').subscribe((p) => expect(p.items).toEqual([]));
    const runs = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/scenarios/runs');
    expect(runs.request.params.get('cursor')).toBe('c1');
    runs.flush({ items: [], nextCursor: null });
    service.getScenarioRun('demo-1').subscribe((r) => expect(r.runId).toBe('demo-1'));
    http.expectOne('/api/v1/scenarios/runs/demo-1').flush({ runId: 'demo-1' });
  });

  it('reads the durable audit trail and transport attempts for an instruction', () => {
    service.getAudit('iid-1').subscribe((a) => expect(a.items).toEqual([]));
    http.expectOne('/api/v1/instructions/iid-1/audit').flush({ instructionId: 'iid-1', items: [] });
    service.getAttempts('iid-1').subscribe((a) => expect(a.items).toEqual([]));
    http
      .expectOne('/api/v1/instructions/iid-1/attempts')
      .flush({ instructionId: 'iid-1', items: [] });
  });

  it('reads the risk summary with a bounded window', () => {
    service.getRiskSummary(15).subscribe((s) => expect(s.mode).toBe('RULES_PLUS_MOCK'));
    const req = http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/risk/summary');
    expect(req.request.params.get('windowMinutes')).toBe('15');
    req.flush({ mode: 'RULES_PLUS_MOCK', riskHeld: 1, reviewRequired: 1, notAssessed: 0 });
  });

  it('lists held risk assessments with filters and reads the cursor header', () => {
    service
      .listRiskAssessments({ assessment: 'REVIEW', reason: 'NEW_RECIPIENT', q: 'SYN-1', limit: 20 })
      .subscribe((page) => {
        expect(page.items).toEqual([]);
        expect(page.nextCursor).toBe('cursor-9');
      });
    const req = http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/v1/risk/assessments' &&
        r.params.get('assessment') === 'REVIEW' &&
        r.params.get('reason') === 'NEW_RECIPIENT' &&
        r.params.get('q') === 'SYN-1' &&
        r.params.get('limit') === '20',
    );
    req.flush({ items: [] }, { headers: { 'X-Next-Cursor': 'cursor-9' } });
  });

  it('reads one risk assessment detail by instruction id', () => {
    service.getRiskAssessment('iid-1').subscribe((d) => expect(d.riskOutput).toBe('REVIEW'));
    http.expectOne('/api/v1/risk/assessments/iid-1').flush({ riskOutput: 'REVIEW' });
  });

  it('returns null for a missing metric instead of inventing zero', () => {
    service.getMetric('queuedesk.outbox.pending').subscribe((m) => expect(m.value).toBe(7));
    http
      .expectOne('/actuator/metrics/queuedesk.outbox.pending')
      .flush({ measurements: [{ value: 7 }] });
    service.getMetric('nope').subscribe((m) => expect(m.value).toBeNull());
    http.expectOne('/actuator/metrics/nope').flush({}, { status: 404, statusText: 'Not Found' });
  });
});
