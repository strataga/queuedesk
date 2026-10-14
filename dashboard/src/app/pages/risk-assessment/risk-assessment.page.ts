import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { RiskAssessmentDetail } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { MinorCurrencyPipe } from '../../shared/minor-currency.pipe';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

// Risk assessment detail (mockup 05): one held instruction's frozen features,
// matched deterministic rules, mock typed signals and evaluation timeline from
// GET /api/v1/risk/assessments/{instructionId}. Confidence is always quoted
// with the backend's own note — it is never the probability of fraud. A
// rules-only assessment has no signals and no provider stage; a null traceId
// means the trace was never sampled, never a link to nowhere.
@Component({
  selector: 'qd-risk-assessment',
  imports: [
    CommonModule,
    RouterLink,
    MinorCurrencyPipe,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './risk-assessment.page.html',
})
export class RiskAssessmentPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);

  protected readonly id$: Observable<string> = this.route.paramMap.pipe(
    map((params) => params.get('instructionId') ?? ''),
  );
  protected readonly view$: Observable<PollResult<RiskAssessmentDetail>> = this.id$.pipe(
    switchMap((id) => this.polling.poll(() => this.api.getRiskAssessment(id))),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected isUnknownId(error: unknown): boolean {
    return (
      error !== null && typeof error === 'object' && (error as { status?: unknown }).status === 404
    );
  }

  protected featureRows(features: Record<string, unknown>): Array<{ key: string; value: string }> {
    return Object.entries(features).map(([key, value]) => ({
      key,
      value: typeof value === 'string' ? value : JSON.stringify(value),
    }));
  }

  protected confidencePct(confidence: number | null): string {
    if (confidence === null || Number.isNaN(confidence)) return 'unknown';
    return confidence.toFixed(2);
  }
}
