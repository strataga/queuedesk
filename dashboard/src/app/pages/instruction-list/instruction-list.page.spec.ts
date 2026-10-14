/**
 * Payments list behavior: URL rescopes reset local filters, late load-more
 * pages from a retired scope are ignored, the census row follows run scope
 * (lab-wide fallback, window-aware), and unassessed RECEIVED rows read
 * Pending only while the risk gate is on.
 */
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, map, of } from 'rxjs';
import { InstructionListPage } from './instruction-list.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

describe('InstructionListPage rescope', () => {
  const params$ = new BehaviorSubject(new Map([['runId', 'A']]));
  const routeStub = {
    snapshot: { queryParamMap: { get: () => null } },
    queryParamMap: params$.pipe(map((m) => ({ get: (k: string) => m.get(k) ?? null }))),
  };
  // Head polls answer immediately from the current scope page; loadMore pages
  // hang on a Subject so the test controls exactly when they arrive.
  const row = (id: string) => ({
    instructionId: id,
    status: 'RECEIVED',
    reason: null,
    receivedAt: '2026-10-05T00:00:00Z',
    updatedAt: '2026-10-05T00:00:00Z',
    statusUrl: `/api/v1/instructions/${id}`,
    correlationId: `corr-${id}`,
    delivery: 'PENDING_PUBLISH',
    payload: {
      clientReference: `ref-${id}`,
      runId: 'A',
      senderParticipant: 'SENDER_A',
      receiverParticipant: 'RECEIVER_B',
      amountMinor: 100,
      currency: 'USD',
    },
  });
  let head: { items: unknown[]; nextCursor: string | null } = { items: [], nextCursor: null };
  let loadMore$: Subject<{ items: unknown[]; nextCursor: string | null }>;

  beforeEach(async () => {
    head = { items: [row('a1')], nextCursor: 'c1' };
    loadMore$ = new Subject();
    params$.next(new Map([['runId', 'A']]));
    await TestBed.configureTestingModule({
      imports: [InstructionListPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: routeStub },
        {
          provide: ApiService,
          useValue: {
            listInstructions: (args: { cursor?: string }) =>
              args.cursor === undefined || args.cursor === null ? of(head) : loadMore$,
            getRun: (runId: string) =>
              of({
                runId,
                admitted: 1,
                byState: { RECEIVED: 1 },
                outboxPending: 0,
                outboxPublished: 0,
                reconcileOpen: 0,
              }),
            getOverview: () =>
              of({
                admitted: 0,
                byState: {},
                outboxPending: 0,
                outboxPublished: 0,
                reconcileOpen: 0,
                since: null,
                until: null,
              }),
            getRiskSummary: () =>
              of({
                asOf: '',
                mode: 'OFF',
                riskHeld: 0,
                reviewRequired: 0,
                notAssessed: 0,
                note: '',
              }),
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
  });

  function componentOf(fixture: { componentInstance: unknown }): Record<string, any> {
    return fixture.componentInstance as Record<string, any>;
  }

  it('resets since/until/limit when a URL rescope carries runId only', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = componentOf(fixture);
    page['sinceLocal'] = '2026-10-01T00:00';
    page['untilLocal'] = '2026-10-02T00:00';
    page['limit'] = 100;
    params$.next(new Map([['runId', 'B']]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page['runId']).toBe('B');
    expect(page['sinceLocal']).toBe('');
    expect(page['untilLocal']).toBe('');
    expect(page['limit']).toBe(25);
  });

  it('ignores a loadMore page that arrives after a rescope', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = componentOf(fixture);
    expect((page['items'] as unknown[]).length).toBe(1);
    page['loadMore']();
    expect(page['loadingMore']).toBe(true);
    // Rescope while the page is in flight; the new head has no cursor.
    head = { items: [row('b1')], nextCursor: null };
    params$.next(new Map([['runId', 'B']]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect((page['items'] as unknown[]).length).toBe(1);
    // The stale page finally arrives: it must not merge into scope B.
    loadMore$.next({ items: [row('stale')], nextCursor: null });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page['items']).toEqual([row('b1')]);
  });
});

describe('InstructionListPage mockup parity', () => {
  const params$ = new BehaviorSubject(new Map([['runId', 'A']]));
  const routeStub = {
    snapshot: { queryParamMap: { get: () => null } },
    queryParamMap: params$.pipe(map((m) => ({ get: (k: string) => m.get(k) ?? null }))),
  };
  const row = (id: string, status: string, riskStatus: string | null) => ({
    instructionId: id,
    status,
    reason: null,
    receivedAt: '2026-10-05T00:00:00Z',
    updatedAt: '2026-10-05T00:00:00Z',
    statusUrl: `/api/v1/instructions/${id}`,
    correlationId: `corr-${id}`,
    delivery: 'PUBLISHED',
    riskStatus,
    assessmentId: null,
    riskReasonCodes: null,
    payload: {
      clientReference: `SYN-${id}`,
      runId: 'A',
      senderParticipant: 'SENDER_A',
      receiverParticipant: 'RECEIVER_B',
      amountMinor: 100,
      currency: 'USD',
    },
  });
  const items = [
    row('a1', 'COMPLETED_SIMULATED', 'ALLOW'),
    row('a2', 'RISK_HELD', 'REVIEW'),
    row('a3', 'PENDING_CONFIRMATION', null),
    row('a4', 'RECEIVED', null),
  ];
  let gateMode = 'OFF';
  let overviewCalls = 0;
  let lastOverviewSince: string | undefined;
  let seenOrders: (string | undefined)[] = [];

  beforeEach(async () => {
    params$.next(new Map([['runId', 'A']]));
    gateMode = 'OFF';
    overviewCalls = 0;
    lastOverviewSince = undefined;
    seenOrders = [];
    await TestBed.configureTestingModule({
      imports: [InstructionListPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: routeStub },
        {
          provide: ApiService,
          useValue: {
            listInstructions: (args: { status?: string; order?: string }) => {
              seenOrders.push(args.order);
              return args.status === 'PENDING_CONFIRMATION'
                ? of({ items: [items[2]], nextCursor: null })
                : of({ items, nextCursor: null });
            },
            getRun: (runId: string) =>
              of({
                runId,
                admitted: 4,
                byState: {
                  COMPLETED_SIMULATED: 1,
                  RISK_HELD: 1,
                  PENDING_CONFIRMATION: 1,
                  RECEIVED: 1,
                },
                outboxPending: 0,
                outboxPublished: 4,
                reconcileOpen: 1,
              }),
            getOverview: (since?: string) => {
              overviewCalls++;
              lastOverviewSince = since;
              return of({
                admitted: 1240,
                byState: {
                  COMPLETED_SIMULATED: 1208,
                  RISK_HELD: 18,
                  RECEIVED: 10,
                  PENDING_CONFIRMATION: 4,
                },
                outboxPending: 0,
                outboxPublished: 1240,
                reconcileOpen: 4,
                since: since ?? null,
                until: null,
              });
            },
            getRiskSummary: () =>
              of({
                asOf: '',
                mode: gateMode,
                riskHeld: 0,
                reviewRequired: 0,
                notAssessed: 0,
                note: '',
              }),
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
  });

  function componentOf(fixture: { componentInstance: unknown }): Record<string, any> {
    return fixture.componentInstance as Record<string, any>;
  }

  it('renders a run-scoped census from run accounting', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const census = el.querySelector('section[aria-label="Payment census"]');
    expect(census).not.toBeNull();
    expect(census?.textContent).toContain('Risk held');
    expect(census?.textContent).toContain('Unknown confirmation');
    expect(census?.textContent).toContain('Simulated completed');
    expect(census?.textContent).toContain('scope: run A');
  });

  it('falls back to the lab-wide census without a run scope', async () => {
    params$.next(new Map());
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(overviewCalls).toBe(1);
    expect(lastOverviewSince).toBeDefined();
    const census = (fixture.nativeElement as HTMLElement).querySelector(
      'section[aria-label="Payment census"]',
    );
    expect(census?.textContent).toContain('1,240');
    expect(census?.textContent).toContain('Last 15 minutes · lab-wide');
  });

  it('re-polls the lab census when the window changes', async () => {
    params$.next(new Map());
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(overviewCalls).toBe(1);
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'select[aria-label="Census window"]',
    ) as HTMLSelectElement;
    select.value = '0';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(overviewCalls).toBe(2);
    expect(lastOverviewSince).toBeUndefined();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('All time');
  });

  it('marks unassessed RECEIVED rows Pending only while the gate is on', async () => {
    gateMode = 'RULES_PLUS_MOCK';
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    // a4 is RECEIVED without an assessment: genuinely awaiting evaluation.
    const rows = Array.from(el.querySelectorAll('section[aria-label^="Results"] tbody tr'));
    const a4 = rows.find((r) => (r.textContent ?? '').includes('SYN-a4'));
    expect(a4?.textContent).toContain('Pending');
    // a3 is past the gate already: no assessment means the dash, not Pending.
    const a3 = rows.find((r) => (r.textContent ?? '').includes('SYN-a3'));
    expect(a3?.querySelectorAll('td')[3].textContent).not.toContain('Pending');
    expect(a3?.querySelector('span[title="No assessment recorded"]')).not.toBeNull();
  });

  it('shows dashes instead of Pending while the gate is off', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const rows = Array.from(el.querySelectorAll('section[aria-label^="Results"] tbody tr'));
    const riskCells = rows.map((r) => r.querySelectorAll('td')[3].textContent ?? '');
    expect(riskCells.some((t) => t.includes('Pending'))).toBe(false);
    expect(el.querySelectorAll('span[title="No assessment recorded"]').length).toBe(2);
  });

  it('renders mockup columns with an Age header and row open links', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const table = el.querySelector('section[aria-label^="Results"] table');
    expect(table).not.toBeNull();
    expect(table?.querySelector('thead')?.textContent).toContain('Age');
    expect(table?.querySelector('thead')?.textContent).not.toContain('Run');
    expect(table?.querySelector('a[aria-label="Open SYN-a1"]')).not.toBeNull();
  });

  it('pages newest-first like the mockup', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(seenOrders.length).toBeGreaterThan(0);
    expect(seenOrders.every((o) => o === 'desc')).toBe(true);
  });

  it('renders reconciliation below the results like the mockup', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const results = el.querySelector('section[aria-label^="Results"]');
    const recon = el.querySelector('section[aria-label="Reconciliation"]');
    expect(results).not.toBeNull();
    expect(recon).not.toBeNull();
    expect(
      results!.compareDocumentPosition(recon!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('filters loaded rows by search and says the search is client-side', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = componentOf(fixture);
    page['search'] = 'SYN-a2';
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('SYN-a2');
    expect(text).not.toContain('SYN-a1');
    expect(text).toMatch(/loaded rows only/);
  });

  it('renders risk badges and a reconciliation panel with an exact count', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('REVIEW');
    expect(text).toMatch(/1 confirmation needs reconciliation|1 confirmations need reconciliation/);
    expect(text).toContain('Business state ≠ transport state');
  });

  it('maps the time-range selector onto the real since filter', async () => {
    const fixture = TestBed.createComponent(InstructionListPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = componentOf(fixture);
    page['setTimeRange']('15m');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page['sinceLocal']).not.toBe('');
    page['setTimeRange']('all');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page['sinceLocal']).toBe('');
    expect(page['untilLocal']).toBe('');
  });
});
