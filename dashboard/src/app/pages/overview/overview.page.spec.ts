import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { OverviewPage } from './overview.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

const row = (id: string, riskStatus: string | null) => ({
  instructionId: id,
  status: 'RISK_HELD',
  reason: null,
  receivedAt: '2026-10-05T00:00:00Z',
  updatedAt: '2026-10-05T00:00:00Z',
  statusUrl: `/api/v1/instructions/${id}`,
  correlationId: `corr-${id}`,
  delivery: 'PUBLISHED',
  riskStatus,
  assessmentId: riskStatus ? 'aid-1' : null,
  riskReasonCodes: riskStatus ? ['NEW_RECIPIENT'] : null,
  payload: {
    clientReference: `SYN-${id}`,
    runId: 'A',
    senderParticipant: 'SENDER_A',
    receiverParticipant: 'RECEIVER_B',
    amountMinor: 850000,
    currency: 'USD',
  },
});

const heldPage = {
  items: [
    {
      instructionId: 'iid-1047',
      clientReference: 'SYN-1047',
      amountMinor: 850000,
      currency: 'USD',
      businessOutcome: 'RISK_HELD',
      riskOutput: 'REVIEW',
      reasonCodes: ['NEW_RECIPIENT', 'UNUSUAL_AMOUNT'],
    },
  ],
  nextCursor: null,
  mode: 'RULES_PLUS_MOCK',
};

const telemetry = {
  asOf: '2026-10-04T10:15:00Z',
  windowMinutes: 15,
  admission: { p95Ms: 22, n: 1240, note: 'ends at HTTP 202' },
  completion: { p95Ms: 420, n: 1208, note: 'ends at durable outcome commit; unresolved excluded' },
  riskProvider: { p95Ms: 95, n: 1226, mode: 'RULES_PLUS_MOCK' },
  sampling: { percent: 10, sampledInstructions: 124 },
  exporter: {
    status: 'HEALTHY',
    lastExportAt: '2026-10-04T10:14:58Z',
    spansSent15Min: 3684,
    spansDropped: 0,
    bufferedSpans: 0,
  },
};

const throughput = {
  metric: 'simulated_completions',
  bucketSeconds: 60,
  points: [
    { at: '2026-10-04T10:00:00Z', count: 40 },
    { at: '2026-10-04T10:01:00Z', count: 90 },
    { at: '2026-10-04T10:02:00Z', count: 80 },
  ],
  since: '2026-10-04T10:00:00Z',
  until: '2026-10-04T10:15:00Z',
};

let seenAssessmentFilter: unknown;

function setup(opts: { held?: typeof heldPage; telemetry?: typeof telemetry } = {}) {
  seenAssessmentFilter = undefined;
  return TestBed.configureTestingModule({
    imports: [OverviewPage],
    providers: [
      provideRouter([]),
      {
        provide: ApiService,
        useValue: {
          getRun: (runId: string) =>
            of({
              runId,
              admitted: 2,
              byState: { RISK_HELD: 1, COMPLETED_SIMULATED: 1 },
              outboxPending: 0,
              outboxPublished: 2,
              reconcileOpen: 0,
            }),
          listInstructions: () =>
            of({ items: [row('1', 'REVIEW'), row('2', null)], nextCursor: null }),
          getQuarantine: () =>
            of({ lastReconciledAt: null, stale: false, lastError: null, items: [] }),
          getGuards: () =>
            of({
              outboxPending: 0,
              backlogLimit: 100,
              backlogHalt: false,
              diskFreeBytes: 1024,
              diskReserveBytes: 512,
              diskHalt: false,
              haltTraffic: false,
              reasons: [],
            }),
          getHealth: () => of({ status: 'UP' }),
          getRiskSummary: () =>
            of({
              asOf: '2026-10-04T10:15:00Z',
              mode: 'RULES_PLUS_MOCK',
              riskHeld: 1,
              reviewRequired: 1,
              notAssessed: 0,
              note: 'Assessment output explains the hold. It is not a fraud determination.',
            }),
          getOverview: () =>
            of({
              admitted: 1240,
              byState: {
                COMPLETED_SIMULATED: 1208,
                RISK_HELD: 18,
                PENDING_CONFIRMATION: 4,
                REJECTED: 10,
              },
              outboxPending: 0,
              outboxPublished: 1240,
              reconcileOpen: 4,
              since: null,
              until: null,
            }),
          getQueues: () =>
            of({
              asOf: '2026-10-04T10:15:00Z',
              outbox: {
                pending: 0,
                oldestPendingAgeSeconds: 0,
                lastPublishedAt: '',
                dispatch: 'BOUNDED',
                lastEventId: '',
              },
              mq: {
                connected: true,
                queueManager: 'QDESK',
                depthEstimate: 12,
                depthEstimateNote: '',
                oldestEstimateAgeSeconds: 4,
                activeConsumers: 1,
                consumerState: 'LISTENING',
                lastSendAt: '',
              },
              quarantine: {
                count: 0,
                lastObserved: 0,
                lastObservedAt: '',
                freshnessAgeSeconds: 2,
                stale: false,
              },
              dedupLast15Min: 0,
            }),
          getThroughput: () => of(throughput),
          getRecentPayments: () => of({ items: [row('9', 'REVIEW'), row('8', null)] }),
          getTelemetrySummary: () => of(opts.telemetry ?? telemetry),
          listRiskAssessments: (filters: unknown) => {
            seenAssessmentFilter = filters;
            return of(opts.held ?? heldPage);
          },
          getInstruction: () => of({ traceId: 'trace-9' }),
        },
      },
      {
        provide: PollingService,
        useValue: {
          poll: (fetch: () => ReturnType<typeof of>) =>
            fetch().pipe(map((value) => ({ status: 'ok', value, at: 1 }) as const)),
          describeError: () => 'boom',
        },
      },
    ],
  }).compileComponents();
}

describe('OverviewPage mockup parity', () => {
  it('renders the risk panel with the latest held instruction and reasons', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const panel = el.querySelector('section[aria-label="Risk review"]');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('Gate RULES_PLUS_MOCK');
    expect(panel?.textContent).toContain('SYN-1047');
    expect(panel?.textContent).toContain('Review required');
    expect(panel?.textContent).toContain('NEW_RECIPIENT');
    expect(panel?.textContent).toContain('UNUSUAL_AMOUNT');
    expect(panel?.textContent).toContain('View risk assessments');
    expect(seenAssessmentFilter).toEqual({ limit: 1, assessment: 'REVIEW' });
  });

  it('explains an OFF gate in the risk panel instead of showing a row', async () => {
    await setup({ held: { items: [], nextCursor: null, mode: 'OFF' } });
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const panel = (fixture.nativeElement as HTMLElement).querySelector(
      'section[aria-label="Risk review"]',
    );
    expect(panel?.textContent).toContain('Gate OFF');
    expect(panel?.textContent).toContain('risk gate is off');
  });

  it('renders the instruction path for the newest recent with trace and audit links', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const path = el.querySelector('section[aria-label="Instruction path"]');
    expect(path?.textContent).toContain('SYN-9');
    expect(path?.textContent).toContain('Illustrative path');
    expect(path?.textContent).toContain('Risk gate');
    expect(path?.textContent).toContain('Mock receiver');
    expect(el.querySelector('a[href="/telemetry/traces/trace-9"]')).not.toBeNull();
    expect(path?.textContent).toContain('Durable audit');
  });

  it('adds a risk column to recent instructions with an honest dash for null', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.componentInstance as unknown as Record<string, (id: string) => void>;
    page['openRun']('A');
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent ?? '';
    expect(text).toContain('REVIEW');
    expect(text).toContain('Risk held');
    // The unassessed row renders a dash with an explanatory title, never a badge.
    const scoped = el.querySelector('section[aria-label="Recent instructions"]');
    const dashes = Array.from(
      (scoped ?? el).querySelectorAll('span[title="No assessment recorded"]'),
    );
    expect(dashes.length).toBe(1);
    expect(text).toContain('since page open');
  });

  it('renders lab-wide recents newest-first without scoping to a run', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const table = el.querySelector('section[aria-label="Recent synthetic payments"]');
    expect(table).not.toBeNull();
    expect(table?.textContent).toContain('SYN-9');
    expect(table?.textContent).toContain('SYN-8');
    expect(table?.textContent).toContain('REVIEW');
    expect(table?.querySelectorAll('span[title="No assessment recorded"]').length).toBe(1);
  });

  it('renders the throughput graph from server buckets', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const graph = el.querySelector('section[aria-label="Processing throughput"]');
    expect(graph).not.toBeNull();
    expect(graph?.querySelector('svg')).not.toBeNull();
    expect(graph?.textContent).toContain('Latest bucket: 80');
    expect(graph?.textContent).toContain('/ min');
  });

  it('renders KPI cards with risk held and end-to-end p95', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const census = el.querySelector('section[aria-label="Lab-wide census"]');
    expect(census).not.toBeNull();
    expect(census?.textContent).toContain('1,240');
    expect(census?.textContent).toContain('1,208');
    expect(census?.textContent).toContain('Last 15 minutes');
    expect(census?.textContent).toContain('Risk held');
    expect(census?.textContent).toContain('gate RULES_PLUS_MOCK');
    expect(census?.textContent).toContain('End-to-end p95');
    expect(census?.textContent).toContain('420 ms');
  });

  it('shows a dash on the p95 card when nothing completed in the window', async () => {
    await setup({
      telemetry: {
        ...telemetry,
        completion: {
          p95Ms: -1,
          n: 0,
          note: 'ends at durable outcome commit; unresolved excluded',
        },
      },
    });
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const census = (fixture.nativeElement as HTMLElement).querySelector(
      'section[aria-label="Lab-wide census"]',
    );
    expect(census?.textContent).not.toContain('-1');
    expect(census?.textContent).toContain('n=0');
  });

  it('re-polls the census when the window changes', async () => {
    let calls = 0;
    await TestBed.configureTestingModule({
      imports: [OverviewPage],
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            getRun: () =>
              of({
                runId: 'A',
                admitted: 0,
                byState: {},
                outboxPending: 0,
                outboxPublished: 0,
                reconcileOpen: 0,
              }),
            listInstructions: () => of({ items: [], nextCursor: null }),
            getQuarantine: () =>
              of({ lastReconciledAt: null, stale: false, lastError: null, items: [] }),
            getGuards: () =>
              of({
                outboxPending: 0,
                backlogLimit: 100,
                backlogHalt: false,
                diskFreeBytes: 1024,
                diskReserveBytes: 512,
                diskHalt: false,
                haltTraffic: false,
                reasons: [],
              }),
            getHealth: () => of({ status: 'UP' }),
            getRiskSummary: () =>
              of({
                asOf: '',
                mode: 'OFF',
                riskHeld: 0,
                reviewRequired: 0,
                notAssessed: 0,
                note: '',
              }),
            getOverview: (since?: string) => {
              calls++;
              return of({
                admitted: since ? 7 : 9,
                byState: {},
                outboxPending: 0,
                outboxPublished: 0,
                reconcileOpen: 0,
                since: since ?? null,
                until: null,
              });
            },
            getQueues: () =>
              of({
                asOf: '',
                outbox: {
                  pending: 0,
                  oldestPendingAgeSeconds: 0,
                  lastPublishedAt: '',
                  dispatch: '',
                  lastEventId: '',
                },
                mq: {
                  connected: true,
                  queueManager: 'QDESK',
                  depthEstimate: 0,
                  depthEstimateNote: '',
                  oldestEstimateAgeSeconds: 0,
                  activeConsumers: 1,
                  consumerState: '',
                  lastSendAt: '',
                },
                quarantine: {
                  count: 0,
                  lastObserved: 0,
                  lastObservedAt: '',
                  freshnessAgeSeconds: 0,
                  stale: false,
                },
                dedupLast15Min: 0,
              }),
            getThroughput: () =>
              of({
                metric: 'simulated_completions',
                bucketSeconds: 60,
                points: [],
                since: '',
                until: '',
              }),
            getRecentPayments: () => of({ items: [] }),
            getTelemetrySummary: () =>
              of({
                asOf: '',
                windowMinutes: 15,
                admission: { p95Ms: -1, n: 0, note: '' },
                completion: { p95Ms: -1, n: 0, note: '' },
                riskProvider: null,
                sampling: { percent: 10, sampledInstructions: 0 },
                exporter: {
                  status: 'DOWN',
                  lastExportAt: null,
                  spansSent15Min: 0,
                  spansDropped: 0,
                  bufferedSpans: 0,
                },
              }),
            listRiskAssessments: () => of({ items: [], nextCursor: null, mode: 'OFF' }),
            getInstruction: () => of({ traceId: null }),
          },
        },
        {
          provide: PollingService,
          useValue: {
            poll: (fetch: () => ReturnType<typeof of>) =>
              fetch().pipe(map((value) => ({ status: 'ok', value, at: 1 }) as const)),
            describeError: () => 'boom',
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(calls).toBe(1);
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'select[aria-label="Census window"]',
    ) as HTMLSelectElement;
    select.value = '0';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(calls).toBe(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('All time');
  });

  it('renders the MQ health panel and the scenarios link', async () => {
    await setup();
    const fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const panel = el.querySelector('section[aria-label="MQ queue health"]');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('12 ready');
    expect(panel?.textContent).toContain('QUEUEDESK.INSTRUCTION.IN');
    expect(el.querySelector('a[href="/scenarios"]')).not.toBeNull();
  });
});
