import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BehaviorSubject, Observable, Subscription, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  KNOWN_REASON_CODES,
  KNOWN_RISK_RESULTS,
  RiskHeldItem,
  RiskSummary,
} from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { MinorCurrencyPipe } from '../../shared/minor-currency.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

const MAX_LOADED_ROWS = 500;

/**
 * Risk review queue (read-only): held payments with verdict, reason, and
 * full-text filters that map 1:1 onto real query parameters, paged by the
 * same cursor contract as the payments list (paging deeper pauses
 * auto-refresh). Review actions are deliberately deferred: this page links
 * to assessments and audit trails only and changes nothing.
 */
@Component({
  selector: 'qd-risk-review',
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MinorCurrencyPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './risk-review.page.html',
})
export class RiskReviewPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly destroyRef = inject(DestroyRef);
  // Zoneless app (no zone.js): manual subscriptions must mark for check.
  private readonly cdr = inject(ChangeDetectorRef);
  private pollSub: Subscription | null = null;
  private loadMoreSub: Subscription | null = null;
  private scope = 0;

  protected readonly knownResults = KNOWN_RISK_RESULTS;
  protected readonly knownReasons = KNOWN_REASON_CODES;
  protected readonly maxRows = MAX_LOADED_ROWS;
  protected readonly windows = [5, 15, 30, 60];

  protected windowMinutes = 15;
  protected assessment = '';
  protected reason = '';
  protected q = '';

  protected items: RiskHeldItem[] | null = null;
  protected nextCursor: string | null = null;
  protected loadingMore = false;
  protected live = true;
  protected fresh = true;
  protected lastAt: number | null = null;
  protected failure: string | null = null;
  protected capped = false;

  private readonly window$ = new BehaviorSubject<number>(15);
  protected readonly summary$: Observable<PollResult<RiskSummary>> = this.window$.pipe(
    takeUntilDestroyed(this.destroyRef),
    switchMap((windowMinutes) => this.polling.poll(() => this.api.getRiskSummary(windowMinutes))),
  );

  ngOnInit(): void {
    this.applyFilters();
    this.destroyRef.onDestroy(() => {
      this.pollSub?.unsubscribe();
      this.loadMoreSub?.unsubscribe();
    });
  }

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected setWindow(minutes: number): void {
    this.windowMinutes = minutes;
    this.window$.next(minutes);
  }

  protected applyFilters(): void {
    this.pollSub?.unsubscribe();
    this.loadMoreSub?.unsubscribe();
    this.loadMoreSub = null;
    this.loadingMore = false;
    this.scope++;
    this.items = null;
    this.nextCursor = null;
    this.live = true;
    this.fresh = true;
    this.failure = null;
    this.capped = false;
    const filters = this.filters();
    this.pollSub = this.polling
      .poll(() => this.api.listRiskAssessments({ ...filters, cursor: undefined }))
      .subscribe((result) => {
        if (result.status === 'ok') {
          this.items = result.value.items;
          this.nextCursor = result.value.nextCursor;
          this.lastAt = result.at;
          this.fresh = true;
          this.failure = null;
        } else {
          this.fresh = false;
          this.lastAt = result.lastOkAt ?? this.lastAt;
          this.failure = this.polling.describeError(result.error);
        }
        this.cdr.markForCheck();
      });
  }

  protected loadMore(): void {
    if (this.nextCursor === null || this.loadingMore || this.items === null) return;
    this.loadingMore = true;
    this.live = false;
    this.pollSub?.unsubscribe();
    this.pollSub = null;
    const filters = this.filters();
    const token = this.scope;
    this.loadMoreSub?.unsubscribe();
    this.loadMoreSub = this.api
      .listRiskAssessments({ ...filters, cursor: this.nextCursor })
      .subscribe({
        next: (page) => {
          if (token !== this.scope) return;
          const merged = [...(this.items ?? []), ...page.items].slice(0, MAX_LOADED_ROWS);
          this.capped = (this.items?.length ?? 0) + page.items.length > MAX_LOADED_ROWS;
          this.items = merged;
          this.nextCursor = this.capped ? null : page.nextCursor;
          this.loadingMore = false;
          this.cdr.markForCheck();
        },
        error: (error: unknown) => {
          if (token !== this.scope) return;
          this.loadingMore = false;
          this.fresh = false;
          this.failure = this.polling.describeError(error);
          this.cdr.markForCheck();
        },
      });
  }

  /** REVIEW holds as a rounded percent of all risk holds. */
  protected reviewShare(sum: RiskSummary): number {
    if (sum.riskHeld <= 0) return 0;
    return Math.round((sum.reviewRequired / sum.riskHeld) * 100);
  }

  /** Sanity check shown in the UI: held must equal review-required plus not-assessed. */
  protected equationHolds(sum: RiskSummary): boolean {
    return sum.riskHeld === sum.reviewRequired + sum.notAssessed;
  }

  private filters() {
    return {
      assessment: this.assessment === '' ? undefined : this.assessment,
      reason: this.reason === '' ? undefined : this.reason,
      q: this.q.trim() === '' ? undefined : this.q.trim(),
      limit: 20,
    };
  }
}
