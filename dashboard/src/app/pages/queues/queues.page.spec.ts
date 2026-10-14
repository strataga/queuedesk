/**
 * Queues page honesty: a stale quarantine count renders unavailable (never
 * zero), and the backlog trend is labeled as samples since page open.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { map, of } from 'rxjs';
import { QueuesPage } from './queues.page';
import { ApiService } from '../../core/api.service';
import { PollingService } from '../../core/polling.service';
import { QueuesView } from '../../core/models';

const snap = (over: Partial<QueuesView> = {}): QueuesView => ({
  asOf: '2026-10-04T10:15:00Z',
  outbox: {
    pending: 6,
    oldestPendingAgeSeconds: 250,
    lastPublishedAt: '2026-10-04T10:14:00Z',
    dispatch: 'BOUNDED',
    lastEventId: 'event-1050',
  },
  mq: {
    connected: true,
    queueManager: 'QDESK',
    depthEstimate: 12,
    depthEstimateNote: 'APPLICATION_ESTIMATE',
    oldestEstimateAgeSeconds: 4.2,
    activeConsumers: 1,
    consumerState: 'LISTENING',
    lastSendAt: '2026-10-04T10:14:58Z',
  },
  quarantine: {
    count: null,
    lastObserved: 0,
    lastObservedAt: '2026-10-04T10:12:30Z',
    freshnessAgeSeconds: 150,
    stale: true,
  },
  dedupLast15Min: 5,
  ...over,
});

function setup(view: QueuesView) {
  return TestBed.configureTestingModule({
    imports: [QueuesPage],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: { getQueues: () => of(view) } },
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

describe('QueuesPage', () => {
  it('renders a stale quarantine count as unavailable, never zero', async () => {
    await setup(snap());
    const fixture = TestBed.createComponent(QueuesPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Stale');
    expect(text).toContain('Last observed: 0');
    // The quarantine card must not present a bare zero as the current count.
    expect(text).not.toMatch(/Quarantine\s*0\b/);
  });

  it('accumulates backlog samples client-side and labels them honestly', async () => {
    await setup(snap());
    const fixture = TestBed.createComponent(QueuesPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('since page open');
    const page = fixture.componentInstance as unknown as Record<string, Array<unknown>>;
    expect((page['backlog'] as Array<unknown>).length).toBeGreaterThan(0);
  });
});
