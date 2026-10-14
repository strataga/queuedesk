/**
 * Run detail routing: navigating /runs/A to /runs/B follows the param
 * observable (no stale snapshot polling), and pending or risk-held work
 * raises the needs-attention panel.
 */
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BehaviorSubject, map, of } from 'rxjs';
import { RunDetailPage } from './run-detail.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

describe('RunDetailPage route params', () => {
  const params$ = new BehaviorSubject(new Map([['runId', 'A']]));
  const requested: string[] = [];
  const fakeRun = (runId: string) => ({
    runId,
    admitted: 4,
    byState: { COMPLETED_SIMULATED: 1, PENDING_CONFIRMATION: 2, RISK_HELD: 1 },
    outboxPending: 0,
    outboxPublished: 1,
    reconcileOpen: 2,
  });
  // Snapshot stays fixed at A while paramMap emits A then B: a snapshot read
  // can never observe B, so this fails against the old implementation.
  const routeStub = {
    snapshot: { paramMap: { get: () => 'A' } },
    paramMap: params$.pipe(map((m) => ({ get: (k: string) => m.get(k) ?? null }))),
  };

  beforeEach(async () => {
    requested.length = 0;
    params$.next(new Map([['runId', 'A']]));
    await TestBed.configureTestingModule({
      imports: [RunDetailPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: routeStub },
        {
          provide: ApiService,
          useValue: {
            getRun: (runId: string) => {
              requested.push(runId);
              return of(fakeRun(runId));
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

  it('follows /runs/A -> /runs/B instead of polling the snapshot', async () => {
    const fixture = TestBed.createComponent(RunDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(requested).toEqual(['A']);
    params$.next(new Map([['runId', 'B']]));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(requested).toEqual(['A', 'B']);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('B');
  });

  it('shows a needs-attention panel for pending and risk-held work', async () => {
    const fixture = TestBed.createComponent(RunDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Needs attention');
    expect(text).toContain('unknown confirmations');
    expect(text).toContain('risk-held');
    expect(text).toContain('open risk review');
    expect(text).toContain('Risk held');
  });
});
