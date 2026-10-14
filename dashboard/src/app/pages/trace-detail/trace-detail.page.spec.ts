import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BehaviorSubject, map, of } from 'rxjs';
import { TraceDetailPage } from './trace-detail.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

const detail = {
  traceId: 'trc-1048-a7',
  instructionId: 'iid-1',
  clientReference: 'SYN-1048',
  participants: 'SENDER_A → RECEIVER_B',
  sampledPercent: 10,
  businessOutcome: 'COMPLETED_SIMULATED',
  riskDecision: 'ALLOW',
  riskMode: 'RULES_PLUS_MOCK',
  endToEndMs: 364,
  admissionMs: 22,
  spans: [
    {
      spanId: 's1',
      parentSpanId: null,
      name: 'queuedesk.worker.attempt',
      startOffsetMs: 75,
      durationMs: 215,
      attributes: { 'messaging.event.id': 'msg-1048-01' },
    },
    {
      spanId: 's2',
      parentSpanId: 's1',
      name: 'queuedesk.risk.evaluate',
      startOffsetMs: 87,
      durationMs: 95,
      attributes: {},
    },
  ],
  asyncLinks: [{ fromSpanId: 's1', toSpanId: 's2', via: 'msg-1048-01' }],
  auditUrl: '/api/v1/instructions/iid-1',
};

describe('TraceDetailPage', () => {
  const params$ = new BehaviorSubject(new Map([['traceId', 'trc-1048-a7']]));
  let seenId = '';

  beforeEach(async () => {
    seenId = '';
    params$.next(new Map([['traceId', 'trc-1048-a7']]));
    await TestBed.configureTestingModule({
      imports: [TraceDetailPage],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params$.pipe(map((m) => ({ get: (k: string) => m.get(k) ?? null }))),
          },
        },
        {
          provide: ApiService,
          useValue: {
            getTrace: (id: string) => {
              seenId = id;
              return of(detail);
            },
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

  it('polls the trace id from the route and repolls when it changes', async () => {
    const fixture = TestBed.createComponent(TraceDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(seenId).toBe('trc-1048-a7');
    params$.next(new Map([['traceId', 'other']]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(seenId).toBe('other');
  });

  it('renders real span names with a non-additive waterfall caption', async () => {
    const fixture = TestBed.createComponent(TraceDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('queuedesk.worker.attempt');
    expect(text).toContain('queuedesk.risk.evaluate');
    expect(text).toContain('not additive');
  });
});
