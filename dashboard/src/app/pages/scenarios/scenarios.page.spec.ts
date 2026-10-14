/**
 * Scenarios page: the backend command preview is shown verbatim (flags are
 * never re-derived client-side) and the search box filters the catalog.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { ScenariosPage } from './scenarios.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';

const catalog = {
  items: [
    {
      id: 'SC02',
      title: 'Duplicates',
      group: 'CORE',
      blurb: '20 unique + 5 repeats',
      seed: 42,
      unique: 20,
      repeats: 5,
      maxAttempts: 25,
      riskMode: 'OFF',
      commandPreview: 'scripts/generate --scenario duplicate --seed 42 --count 20 --run-id <run>',
      commandArgs: ['scripts/generate'],
      dryRunDefault: true,
    },
    {
      id: 'RS05',
      title: 'Provider timeout',
      group: 'RISK',
      blurb: 'Risk held / Not assessed',
      seed: 7,
      unique: 5,
      repeats: 0,
      maxAttempts: 5,
      riskMode: 'RULES_PLUS_MOCK',
      commandPreview: 'scripts/risk-eval.py rs05',
      commandArgs: ['scripts/risk-eval.py'],
      dryRunDefault: true,
    },
  ],
};

const runs = {
  items: [
    {
      runId: 'demo-0042',
      scenarioId: 'SC02',
      scenarioTitle: 'Duplicates',
      startedAt: '2026-10-04T10:12:00Z',
      admitted: 20,
      summary: '20 admitted · 5 repeat responses',
    },
  ],
  nextCursor: null,
};

function setup() {
  return TestBed.configureTestingModule({
    imports: [ScenariosPage],
    providers: [
      provideRouter([]),
      {
        provide: ApiService,
        useValue: { listScenarios: () => of(catalog), listScenarioRuns: () => of(runs) },
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

describe('ScenariosPage', () => {
  it('shows the backend command preview verbatim, never re-derived flags', async () => {
    await setup();
    const fixture = TestBed.createComponent(ScenariosPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain(
      'scripts/generate --scenario duplicate --seed 42 --count 20 --run-id <run>',
    );
    expect(text).not.toContain('--dry-run');
    expect(text).not.toContain('--unique');
  });

  it('filters the catalog by the search box', async () => {
    await setup();
    const fixture = TestBed.createComponent(ScenariosPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.componentInstance as unknown as Record<string, string>;
    page['query'] = 'provider';
    fixture.detectChanges();
    await fixture.whenStable();
    // Scope to the catalog: the runs table below legitimately still names SC02.
    const catalog = (fixture.nativeElement as HTMLElement).querySelector(
      '[aria-label="Scenario catalog"]',
    );
    const text = catalog?.textContent ?? '';
    expect(text).toContain('RS05');
    expect(text).not.toContain('SC02');
  });
});
