import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { InstructionDetailPage } from './instruction-detail.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

const item = (over: Record<string, unknown> = {}) => ({
  instructionId: 'iid-1044', status: 'PENDING_CONFIRMATION', reason: 'TIMEOUT',
  receivedAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:01:00Z',
  statusUrl: '/api/v1/instructions/iid-1044', correlationId: 'corr-1044',
  delivery: 'PUBLISHED', riskStatus: 'ALLOW', assessmentId: 'aid-1',
  riskReasonCodes: [],
  payload: { clientReference: 'SYN-1044', runId: 'demo-0041', senderParticipant: 'SENDER_A',
    receiverParticipant: 'RECEIVER_B', amountMinor: 125000, currency: 'USD' },
  ...over,
});

const audit = {
  instructionId: 'iid-1044',
  items: [
    { oldState: 'RECEIVED', newState: 'BUSINESS_ACCEPTED', reason: null, actor: 'worker', recordedAt: '2026-10-05T00:00:30Z' },
  ],
};

const attempts = {
  instructionId: 'iid-1044',
  items: [
    { eventId: 'event-1044', result: 'COMPLETED_SIMULATED', reason: null, deliveryCount: 1,
      startedAt: '2026-10-05T00:00:10Z', endedAt: '2026-10-05T00:00:20Z' },
  ],
};

function setup(value: Record<string, unknown>) {
  return TestBed.configureTestingModule({
    imports: [InstructionDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          paramMap: of({ get: (k: string) => (k === 'id' ? 'iid-1044' : null) }),
        },
      },
      {
        provide: ApiService,
        useValue: {
          getInstruction: () => of(value),
          getAudit: () => of(audit),
          getAttempts: () => of(attempts),
        },
      },
      {
        provide: PollingService,
        useValue: {
          poll: (fetch: () => ReturnType<typeof of>) =>
            fetch().pipe(map((v) => ({ status: 'ok', value: v, at: 1 }) as const)),
          describeError: () => 'boom',
        },
      },
    ],
  }).compileComponents();
}

describe('InstructionDetailPage mockup parity', () => {
  it('renders Timeline/Request/Audit tabs with audit rows and transport events', async () => {
    await setup(item());
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Timeline');
    expect(el.textContent).toContain('Request');
    expect(el.textContent).toContain('Audit');
    expect(el.textContent).toContain('Transport events');
    expect(el.textContent).toContain('event-1044');
    // The Audit tab reveals the durable rows; Request reveals the payload.
    // Clicks drive the tabs like a keyboard/mouse user would.
    const tabs = () => Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[role="tab"]'),
    ) as HTMLElement[];
    tabs()[2].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('BUSINESS_ACCEPTED');
    tabs()[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('SENDER_A');
  });

  it('shows the reconciliation panel for an unknown confirmation', async () => {
    await setup(item());
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Reconciliation');
    expect(text).toMatch(/No resubmit or retry action available/);
    expect(text).toMatch(/timeout does not mean rejection/i);
  });

  it('links a risk-held instruction to its assessment', async () => {
    await setup(item({ status: 'RISK_HELD', riskStatus: 'REVIEW', riskReasonCodes: ['NEW_RECIPIENT'] }));
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Risk held');
    expect(text).toContain('NEW_RECIPIENT');
    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href*="/risk/assessments/"]');
    expect(link).not.toBeNull();
  });

  it('links to the trace when the view carries a trace id', async () => {
    await setup(item({ traceId: '4bf92f3577b34da6a3ce929d0e0e4736' }));
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const link = (fixture.nativeElement as HTMLElement).querySelector(
      'a[href*="/telemetry/traces/4bf92f3577b34da6a3ce929d0e0e4736"]',
    );
    expect(link).not.toBeNull();
    expect(link?.textContent).toContain('View trace');
  });

  it('shows no trace link without a trace id', async () => {
    await setup(item({ traceId: null }));
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('a[href*="/telemetry/traces/"]'),
    ).toBeNull();
  });

  it('offers copy buttons for every identifier', async () => {
    await setup(item());
    const fixture = TestBed.createComponent(InstructionDetailPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('section[aria-label="Identifiers"] button'),
    );
    expect(buttons.length).toBeGreaterThanOrEqual(4);
  });
});
