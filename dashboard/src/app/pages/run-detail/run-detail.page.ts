import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { KNOWN_STATES, RunSummary } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

/**
 * Run detail: admitted/completed/rejected/unresolved accounting from API
 * evidence. Two figures are honestly unknown: the generator persists no
 * "expected unique" manifest, and repeat admissions reuse the existing row
 * without a per-run repeat count — both render as unknown with the reason,
 * never as zero or a guess.
 */
@Component({
  selector: 'qd-run-detail',
  imports: [
    CommonModule,
    RouterLink,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './run-detail.page.html',
})
export class RunDetailPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);

  // paramMap observable, not a snapshot read: the router reuses this component
  // when navigating /runs/A -> /runs/B, and a snapshot would keep polling A
  // while the URL shows B.
  protected readonly runId$: Observable<string> = this.route.paramMap.pipe(
    map((params) => params.get('runId') ?? ''),
  );
  protected readonly knownStates = KNOWN_STATES;
  protected readonly view$: Observable<PollResult<RunSummary>> = this.runId$.pipe(
    switchMap((runId) => this.polling.poll(() => this.api.getRun(runId))),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  /** Count for one business state in this run (0 when absent). */
  protected stateCount(run: RunSummary, state: string): number {
    return run.byState[state] ?? 0;
  }

  /** Payments not yet in a terminal state (received, accepted, pending, or risk-held). */
  protected unresolved(run: RunSummary): number {
    return (
      this.stateCount(run, 'RECEIVED') +
      this.stateCount(run, 'BUSINESS_ACCEPTED') +
      this.stateCount(run, 'PENDING_CONFIRMATION') +
      this.stateCount(run, 'RISK_HELD')
    );
  }

  /** Whether anything awaits confirmation or risk review in this run. */
  protected needsAttention(run: RunSummary): boolean {
    return this.stateCount(run, 'PENDING_CONFIRMATION') + this.stateCount(run, 'RISK_HELD') > 0;
  }
}
