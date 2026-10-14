import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { TelemetryPage } from './telemetry.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';
import { TelemetrySummary } from '../../core/models';

const summary: TelemetrySummary = {
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

const traces = {
  items: [
    {
      instructionId: 'iid-1',
      clientReference: 'SYN-1048',
      traceId: 'trc-1048-a7',
      outcome: 'COMPLETED_SIMULATED',
      elapsedMs: 386,
    },
  ],
  nextCursor: null,
};

const logs = {
  items: [
    { at: '2026-10-06T03:00:00Z', level: 'INFO', logger: 'dev.queuedesk.App', message: 'booted' },
    {
      at: '2026-10-06T03:01:00Z',
      level: 'WARN',
      logger: 'dev.queuedesk.App',
      message: 'slow drain',
    },
  ],
  dropped: 0,
  capacity: 500,
  note: 'live ring',
};

function setup(summaryOverride = summary) {
  return TestBed.configureTestingModule({
    imports: [TelemetryPage],
    providers: [
      provideRouter([]),
      {
        provide: ApiService,
        useValue: {
          getTelemetrySummary: () => of(summaryOverride),
          listTraces: () => of(traces),
          listLogs: () => of(logs),
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

describe('TelemetryPage', () => {
  it('renders latency cards with honest window notes', async () => {
    await setup();
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('22');
    expect(text).toContain('420');
    expect(text).toContain('ends at HTTP 202');
    expect(text).toContain('since page open');
  });

  it('links each sampled trace to its detail page', async () => {
    await setup();
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[role="tablist"] button:nth-child(2)') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    const link = el.querySelector('a[href*="/telemetry/traces/trc-1048-a7"]');
    expect(link).not.toBeNull();
    expect(el.textContent).toContain('SYN-1048');
  });

  it('shows metrics by default and hides traces until its tab opens', async () => {
    await setup();
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('HTTP admission p95');
    expect(el.querySelector('section[aria-label="Recent sampled traces"]')).toBeNull();
  });

  it('renders log rows and narrows them by minimum level', async () => {
    await setup();
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[role="tablist"] button:nth-child(3)') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.textContent).toContain('booted');
    expect(el.textContent).toContain('slow drain');
    const select = el.querySelector(
      'section[aria-label="Process logs"] select',
    ) as HTMLSelectElement;
    select.value = 'WARN';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.textContent).not.toContain('booted');
    expect(el.textContent).toContain('slow drain');
  });

  it('shows dashes instead of the -1 sentinel when a side has no samples', async () => {
    await setup({
      ...summary,
      admission: { p95Ms: -1, n: 0, note: 'ends at HTTP 202' },
      completion: { p95Ms: -1, n: 0, note: 'ends at durable outcome commit; unresolved excluded' },
    });
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('-1');
    expect(el.querySelector('section[aria-label="HTTP admission p95"]')?.textContent).toContain(
      '—',
    );
    expect(
      el.querySelector('section[aria-label="End-to-end completion p95"]')?.textContent,
    ).toContain('—');
    expect(el.textContent).toContain('Collecting samples');
  });

  it('renders honest fallbacks when provider readings and exports are absent', async () => {
    await setup({
      ...summary,
      riskProvider: null,
      exporter: { ...summary.exporter, lastExportAt: null },
    });
    const fixture = TestBed.createComponent(TelemetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Risk gate off · no provider readings');
    expect(el.textContent).toContain('never — no collector configured');
  });
});
