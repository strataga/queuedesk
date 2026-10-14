import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BehaviorSubject, map, of } from 'rxjs';
import { ScenarioRunDetailPage } from './scenario-run-detail.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';
import { ScenarioRunReport } from '../../core/models';

const report: ScenarioRunReport = {
  runId: 'demo-0042', scenarioId: 'SC02', scenarioTitle: 'Duplicates',
  startedAt: '2026-10-04T10:12:00Z', seed: null, riskMode: 'OFF',
  httpAttempts: 25, uniqueAdmitted: 20, simulatedCompleted: 20,
  unknownConfirmations: 0, repeatResponses: 5, duplicateOutcomes: 0,
  assertions: [
    { text: '20 unique instructions admitted', passed: true },
    { text: '0 duplicate business outcomes', passed: false },
  ],
  generator: { scenario: 'duplicate', seed: null, unique: 20, repeats: 5, maxAttempts: 25, participants: 'SENDER_A → RECEIVER_B' },
  commandPreview: 'scripts/generate --scenario duplicate --seed <seed> --count 20 --run-id demo-0042 --send --observe',
  commandArgs: ['scripts/generate'], dryRunDefault: true,
};

describe('ScenarioRunDetailPage', () => {
  const params$ = new BehaviorSubject(new Map([['runId', 'demo-0042']]));
  let seenId = '';
  let current = report;

  beforeEach(async () => {
    seenId = '';
    current = report;
    params$.next(new Map([['runId', 'demo-0042']]));
    await TestBed.configureTestingModule({
      imports: [ScenarioRunDetailPage],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: params$.pipe(map((m) => ({ get: (k: string) => m.get(k) ?? null }))) },
        },
        {
          provide: ApiService,
          useValue: {
            getScenarioRun: (id: string) => {
              seenId = id;
              return of(current);
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

  it('polls the run id from the route', async () => {
    const fixture = TestBed.createComponent(ScenarioRunDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(seenId).toBe('demo-0042');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('demo-0042');
  });

  it('marks failed assertions distinctly from passed ones', async () => {
    const fixture = TestBed.createComponent(ScenarioRunDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const items = [...el.querySelectorAll('[data-assertion]')];
    expect(items.length).toBe(2);
    expect(items[0].getAttribute('data-assertion')).toBe('pass');
    expect(items[1].getAttribute('data-assertion')).toBe('fail');
  });

  it('labels a run without a catalog scenario as ad-hoc', async () => {
    current = { ...report, scenarioId: null, scenarioTitle: null };
    const fixture = TestBed.createComponent(ScenarioRunDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ad-hoc run');
  });
});
