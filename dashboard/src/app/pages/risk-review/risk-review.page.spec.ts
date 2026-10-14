import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { RiskReviewPage } from './risk-review.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';
import { RiskHeldPage, RiskSummary } from '../../core/models';

const summary = (over: Partial<RiskSummary> = {}): RiskSummary => ({
  asOf: '2026-10-04T10:15:00Z',
  mode: 'RULES_PLUS_MOCK',
  riskHeld: 18,
  reviewRequired: 14,
  notAssessed: 4,
  note: 'Assessment output explains the hold. It is not a fraud determination.',
  usage: { calls: 0, inputTokens: 0, outputTokens: 0, costUsd: 0, budgetUsd: 1, exhausted: false },
  ...over,
});

const held: RiskHeldPage = {
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

function setup(sum: RiskSummary, page: RiskHeldPage = held) {
  return TestBed.configureTestingModule({
    imports: [RiskReviewPage],
    providers: [
      provideRouter([]),
      {
        provide: ApiService,
        useValue: {
          getRiskSummary: () => of(sum),
          listRiskAssessments: () => of(page),
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

describe('RiskReviewPage', () => {
  it('renders held counts, the mode, and the hold note verbatim', async () => {
    await setup(summary());
    const fixture = TestBed.createComponent(RiskReviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('18');
    expect(text).toContain('14');
    expect(text).toContain('RULES_PLUS_MOCK');
    expect(text).toContain('It is not a fraud determination.');
    expect(text).toContain('NEW_RECIPIENT');
    expect(text).toContain('SYN-1047');
  });

  it('explains an OFF gate instead of showing an empty queue', async () => {
    await setup(summary({ mode: 'OFF', riskHeld: 0, reviewRequired: 0, notAssessed: 0 }), {
      items: [],
      nextCursor: null,
      mode: 'OFF',
    });
    const fixture = TestBed.createComponent(RiskReviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('OFF');
    expect(text).toMatch(/gate is off|risk gate is off/i);
  });

  it('states that review actions are deferred and the panel is read-only', async () => {
    await setup(summary());
    const fixture = TestBed.createComponent(RiskReviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toMatch(/deferred/i);
    expect(text).toMatch(/read-only/i);
  });

  it('shows the Jev spend ledger and the exhausted alert in JEV mode', async () => {
    await setup(
      summary({
        mode: 'RULES_PLUS_JEV',
        usage: {
          calls: 3,
          inputTokens: 1200,
          outputTokens: 180,
          costUsd: 0.0000504,
          budgetUsd: 1,
          exhausted: true,
        },
      }),
    );
    const fixture = TestBed.createComponent(RiskReviewPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('jev usage: 3 calls');
    expect(text).toContain('1,200');
    expect(text).toContain('budget exhausted');
  });
});
