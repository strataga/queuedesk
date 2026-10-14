/**
 * Risk assessment detail: output text, matched rules, and the confidence
 * note render verbatim; rules-only assessments say so; unsampled traces
 * get no link.
 */
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { RiskAssessmentPage } from './risk-assessment.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';
import { RiskAssessmentDetail } from '../../core/models';

const detail = (over: Partial<RiskAssessmentDetail> = {}): RiskAssessmentDetail => ({
  instructionId: 'iid-1047',
  clientReference: 'SYN-1047',
  participants: 'SENDER_A → RECEIVER_B',
  amountMinor: 850000,
  currency: 'USD',
  businessOutcome: 'RISK_HELD',
  riskOutput: 'REVIEW',
  riskOutputText: 'Review required',
  features: {
    amountMinor: 850000,
    priorUniqueCount: 3,
    elapsedSincePreviousMs: 12000,
    receiverSeenBefore: false,
    amountToBaselineRatio: 8.9,
    asOf: '2026-10-04T10:14:37Z',
    featureVersion: '1.0',
    missingElapsed: false,
    missingBaseline: false,
  },
  matchedRules: [{ code: 'NEW_RECIPIENT', detail: 'recipient_seen_before = false' }],
  policy: 'Any matched rule → REVIEW',
  signals: [
    {
      questionId: 'Q01',
      question: 'Is the recipient new to this sender?',
      type: 'boolean',
      answer: true,
      confidence: 0.96,
      confidenceNote:
        'Confidence is in the answer to the exact question. It is never the probability of fraud.',
    },
  ],
  timeline: [{ stage: 'mock_provider', durationMs: 72 }],
  totalMs: 95,
  assessedAt: '2026-10-04T10:14:38.215Z',
  traceId: 'trc-1047-b2',
  versions: { featureSchema: '1.0', rules: '0.4', policy: '0.4', questions: 'mock-2026-10' },
  ...over,
});

function setup(detailValue: RiskAssessmentDetail) {
  return TestBed.configureTestingModule({
    imports: [RiskAssessmentPage],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          paramMap: of({ get: (k: string) => (k === 'instructionId' ? 'iid-1047' : null) }),
        },
      },
      { provide: ApiService, useValue: { getRiskAssessment: () => of(detailValue) } },
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

describe('RiskAssessmentPage', () => {
  it('renders the output text, matched rules and the confidence note verbatim', async () => {
    await setup(detail());
    const fixture = TestBed.createComponent(RiskAssessmentPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Review required');
    expect(text).toContain('NEW_RECIPIENT');
    expect(text).toContain('Any matched rule → REVIEW');
    expect(text).toContain('It is never the probability of fraud.');
    expect(text).toContain('SYN-1047');
    expect(text).toContain('mock-2026-10');
  });

  it('is honest about a rules-only assessment with no provider signals', async () => {
    await setup(detail({ signals: [], timeline: [], totalMs: null }));
    const fixture = TestBed.createComponent(RiskAssessmentPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toMatch(/no provider signals/i);
    expect(text).not.toContain('0 ms');
  });

  it('is honest when the trace was never sampled', async () => {
    await setup(detail({ traceId: null }));
    const fixture = TestBed.createComponent(RiskAssessmentPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toMatch(/not sampled|no sampled trace/i);
  });
});
