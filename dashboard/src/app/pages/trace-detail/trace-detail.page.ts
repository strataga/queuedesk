import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { TraceDetail, TraceSpan } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

// Trace detail (mockup 08): sampled waterfall from GET /api/v1/telemetry/traces/{id}.
// Span names are the real queuedesk.* names; offsets are relative to request start
// and durations are not additive. An unknown or ring-evicted trace id reports
// "no such trace", never a guess. Selecting a row shows its span attributes.
@Component({
  selector: 'qd-trace-detail',
  imports: [CommonModule, RouterLink, RefreshNoteComponent, StateMessageComponent, StatusBadgeComponent],
  templateUrl: './trace-detail.page.html',
})
export class TraceDetailPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);

  protected readonly traceId$: Observable<string> = this.route.paramMap.pipe(
    map((params) => params.get('traceId') ?? ''),
  );
  protected readonly view$: Observable<PollResult<TraceDetail>> = this.traceId$.pipe(
    switchMap((traceId) => this.polling.poll(() => this.api.getTrace(traceId))),
  );

  protected selectedSpanId: string | null = null;

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected selectSpan(spanId: string): void {
    this.selectedSpanId = this.selectedSpanId === spanId ? null : spanId;
  }

  protected selectedSpan(detail: TraceDetail): TraceSpan | null {
    return detail.spans.find((s) => s.spanId === this.selectedSpanId) ?? null;
  }

  protected spanEntries(span: TraceSpan): Array<{ key: string; value: string }> {
    return Object.entries(span.attributes ?? {}).map(([key, value]) => ({ key, value }));
  }

  protected barLeft(span: TraceSpan, totalMs: number): string {
    return `${totalMs <= 0 ? 0 : (span.startOffsetMs / totalMs) * 100}%`;
  }

  protected barWidth(span: TraceSpan, totalMs: number): string {
    return `${totalMs <= 0 ? 0 : Math.max(1.5, (span.durationMs / totalMs) * 100)}%`;
  }

  protected isRiskSpan(span: TraceSpan): boolean {
    return span.name === 'queuedesk.risk.evaluate';
  }
}
