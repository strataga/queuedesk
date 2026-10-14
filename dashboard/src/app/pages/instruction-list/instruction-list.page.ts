import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  InstructionPage,
  InstructionView,
  KNOWN_RISK_RESULTS,
  KNOWN_STATES,
} from '../../core/models';
import { PollingService } from '../../core/polling.service';
import { MinorCurrencyPipe } from '../../shared/minor-currency.pipe';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

const MAX_LOADED_ROWS = 500;

/**
 * Payments list: run/status/time filters with bounded cursor paging, a
 * scope-aware census row (run accounting when a run ID is set, the
 * lab-wide census otherwise), client-side search and risk narrowing, and
 * a reconciliation watch panel. The first page auto-refreshes every 5s;
 * paging deeper pauses auto-refresh (and says so), because a moving cursor
 * plus a re-polled head would silently drop or duplicate rows. Loaded rows
 * are capped at 500 with an explicit note. The list API has no server-side
 * search or risk filter, so those narrow loaded rows only — labeled as such.
 */
@Component({
  selector: 'qd-instruction-list',
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MinorCurrencyPipe,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './instruction-list.page.html',
})
export class InstructionListPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  // Zoneless app (no zone.js): manual subscriptions must mark for check —
  // nothing else schedules change detection for field assignments.
  private readonly cdr = inject(ChangeDetectorRef);
  private pollSub: Subscription | null = null;
  private loadMoreSub: Subscription | null = null;
  private statsSub: Subscription | null = null;
  private pendingSub: Subscription | null = null;
  private modeSub: Subscription | null = null;
  // Scope token: every applyFilters (URL rescope or manual Apply) retires
  // in-flight loadMore responses so a stale page can never merge into a list
  // that no longer matches its filters.
  private scope = 0;

  protected readonly knownStates = KNOWN_STATES;
  protected readonly knownRiskResults = KNOWN_RISK_RESULTS;
  protected readonly maxRows = MAX_LOADED_ROWS;

  protected runId = '';
  protected status = '';
  protected sinceLocal = '';
  protected untilLocal = '';
  protected limit = 25;
  protected timeRange = 'all';
  protected search = '';
  protected riskFilter = '';

  protected items: InstructionView[] | null = null;
  protected nextCursor: string | null = null;
  protected loadingMore = false;
  protected live = true;
  protected fresh = true;
  protected lastAt: number | null = null;
  protected failure: string | null = null;
  protected capped = false;

  protected census: { admitted: number; byState: Record<string, number> } | null = null;
  protected censusFresh = true;
  protected censusRun = false;
  protected pending: InstructionPage | null = null;
  protected riskMode: string | null = null;

  protected readonly windowOptions = [
    { label: 'Last 15 minutes', minutes: 15 },
    { label: 'Last hour', minutes: 60 },
    { label: 'Last 24 hours', minutes: 1440 },
    { label: 'All time', minutes: 0 },
  ];
  protected windowMinutes = 15;

  ngOnInit(): void {
    // Query-param observable, not a one-shot snapshot read: navigating here
    // from a run page while already on the list reuses this component, and
    // the new scope must apply. Safe from loops: applying filters never
    // rewrites the URL.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.runId = params.get('runId') ?? '';
      this.status = params.get('status') ?? '';
      // The URL carries runId/status only: time/limit/search filters from a
      // previous scope would silently narrow the new one, so they reset.
      this.sinceLocal = '';
      this.untilLocal = '';
      this.timeRange = 'all';
      this.limit = 25;
      this.search = '';
      this.riskFilter = '';
      this.applyFilters();
      this.cdr.markForCheck();
    });
    this.destroyRef.onDestroy(() => {
      this.pollSub?.unsubscribe();
      this.loadMoreSub?.unsubscribe();
      this.statsSub?.unsubscribe();
      this.pendingSub?.unsubscribe();
      this.modeSub?.unsubscribe();
    });
  }

  protected describe = (error: unknown): string => this.polling.describeError(error);

  /**
   * (Re)starts the scoped first-page poll plus census, pending-watch, and
   * gate-mode polls. Bumping the scope token retires any in-flight
   * load-more response from the previous scope.
   */
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
      .poll(() => this.api.listInstructions({ ...filters, cursor: undefined }))
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
    this.resubscribeCensus();
    this.resubscribePending();
    this.resubscribeMode();
  }

  // Census row: run accounting when a run id scopes the page, otherwise the
  // lab-wide overview census inside the KPI window. The caption always names
  // which scope the numbers come from — never a sum of loaded pages.
  private resubscribeCensus(): void {
    this.statsSub?.unsubscribe();
    this.statsSub = null;
    this.census = null;
    this.censusFresh = true;
    const runId = this.runId.trim();
    this.censusRun = runId !== '';
    const fetch: () => Observable<{ admitted: number; byState: Record<string, number> }> =
      runId !== ''
        ? () => this.api.getRun(runId)
        : () => {
            const since =
              this.windowMinutes > 0
                ? new Date(Date.now() - this.windowMinutes * 60_000).toISOString()
                : undefined;
            return this.api.getOverview(since);
          };
    this.statsSub = this.polling.poll(fetch).subscribe((result) => {
      if (result.status === 'ok') {
        this.census = { admitted: result.value.admitted, byState: result.value.byState };
        this.censusFresh = true;
      } else if (this.census === null) {
        this.censusFresh = false;
      }
      this.cdr.markForCheck();
    });
  }

  // Gate mode for the Pending risk badge: polled with the scope, read-only.
  private resubscribeMode(): void {
    this.modeSub?.unsubscribe();
    this.modeSub = null;
    this.riskMode = null;
    this.modeSub = this.polling
      .poll(() => this.api.getRiskSummary())
      .subscribe((result) => {
        if (result.status === 'ok') this.riskMode = result.value.mode;
        this.cdr.markForCheck();
      });
  }

  // Reconciliation watch: a bounded pending-confirmation sample in the same run
  // scope. An exact count when the sample exhausts the cursor, "at least" when
  // more pages remain — never a guessed total.
  private resubscribePending(): void {
    this.pendingSub?.unsubscribe();
    this.pendingSub = null;
    this.pending = null;
    const runId = this.runId.trim();
    this.pendingSub = this.polling
      .poll(() =>
        this.api.listInstructions({
          runId: runId === '' ? undefined : runId,
          status: 'PENDING_CONFIRMATION',
          limit: 5,
          order: 'desc',
        }),
      )
      .subscribe((result) => {
        if (result.status === 'ok') this.pending = result.value;
        this.cdr.markForCheck();
      });
  }

  protected resetFilters(): void {
    this.runId = '';
    this.status = '';
    this.sinceLocal = '';
    this.untilLocal = '';
    this.timeRange = 'all';
    this.limit = 25;
    this.search = '';
    this.riskFilter = '';
    this.windowMinutes = 15;
    this.applyFilters();
  }

  protected setWindow(minutes: string): void {
    const parsed = Number(minutes);
    if (!Number.isInteger(parsed) || parsed < 0) return;
    this.windowMinutes = parsed;
    this.resubscribeCensus();
  }

  protected windowLabel(): string {
    return this.windowOptions.find((o) => o.minutes === this.windowMinutes)?.label ?? 'Custom';
  }

  // Quick time ranges map onto the real since/until filters (local input
  // values, converted to ISO on apply). Manual edits to the datetime inputs
  // take precedence: picking a range overwrites them, clearing them by hand
  // leaves the range label stale but harmless — apply always reads the inputs.
  protected setTimeRange(range: string): void {
    this.timeRange = range;
    if (range === 'all') {
      this.sinceLocal = '';
      this.untilLocal = '';
    } else {
      const minutes = range === '15m' ? 15 : range === '1h' ? 60 : 1440;
      this.sinceLocal = InstructionListPage.toLocalInput(new Date(Date.now() - minutes * 60_000));
      this.untilLocal = '';
    }
    this.applyFilters();
  }

  protected viewPending(): void {
    this.status = 'PENDING_CONFIRMATION';
    this.applyFilters();
  }

  // Client-side narrowing over loaded rows only: the list API offers no server
  // search or risk filter, and the template labels this as loaded-rows-only.
  protected displayed(items: InstructionView[]): InstructionView[] {
    const q = this.search.trim().toLowerCase();
    return items.filter((item) => {
      if (this.riskFilter !== '' && (item.riskStatus ?? 'NONE') !== this.riskFilter) return false;
      if (q === '') return true;
      return `${item.payload.clientReference} ${item.instructionId}`.toLowerCase().includes(q);
    });
  }

  protected pendingLabel(): string | null {
    if (this.pending === null || this.pending.items.length === 0) return null;
    const n = this.pending.items.length;
    const noun = n === 1 ? 'confirmation needs' : 'confirmations need';
    return this.pending.nextCursor === null
      ? `${n} ${noun} reconciliation`
      : `at least ${n} ${noun} reconciliation`;
  }

  protected statCount(state: string): number {
    return this.census?.byState[state] ?? 0;
  }

  // Pending risk badge: with the gate on, a RECEIVED row without an assessment
  // is genuinely awaiting worker-side evaluation. Anything else unassessed —
  // gate OFF, or past the gate already — keeps the honest dash.
  protected riskPending(item: InstructionView): boolean {
    return (
      this.riskMode !== null &&
      this.riskMode !== 'OFF' &&
      item.riskStatus == null &&
      item.status === 'RECEIVED'
    );
  }

  /**
   * Fetches the next cursor page and pauses live polling: a moving cursor
   * over a re-polled head would drop or duplicate rows. Stops at the
   * 500-row cap with the cap made visible.
   */
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
      .listInstructions({ ...filters, cursor: this.nextCursor })
      .subscribe({
        next: (page) => {
          // A rescope retired this request: the new scope owns the list now.
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

  /** Human label of the active filter scope ("all runs" when unfiltered). */
  protected scopeLabel(): string {
    const parts: string[] = [];
    if (this.runId.trim() !== '') parts.push(`run ${this.runId.trim()}`);
    if (this.status !== '') parts.push(`status ${this.status}`);
    if (this.sinceLocal !== '') parts.push(`since ${this.sinceLocal}`);
    if (this.untilLocal !== '') parts.push(`until ${this.untilLocal}`);
    return parts.length === 0 ? 'all runs' : parts.join(' · ');
  }

  // Newest-first like the mockup: the head poll and every loadMore page
  // carry the same order, so cursors stay direction-scoped.
  private filters() {
    return {
      runId: this.runId.trim() === '' ? undefined : this.runId.trim(),
      status: this.status === '' ? undefined : this.status,
      since: InstructionListPage.toIso(this.sinceLocal),
      until: InstructionListPage.toIso(this.untilLocal),
      limit: this.limit,
      order: 'desc' as const,
    };
  }

  private static toIso(local: string): string | undefined {
    if (local === '') return undefined;
    const at = new Date(local).getTime();
    return Number.isNaN(at) ? undefined : new Date(at).toISOString();
  }

  private static toLocalInput(at: Date): string {
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
  }
}
