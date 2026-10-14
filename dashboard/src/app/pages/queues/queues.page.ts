import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Observable, tap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { QueuesView } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { TrendChartComponent, TrendSeries } from '../../shared/trend-chart.component';

interface BacklogSample {
  at: number;
  outbox: number;
  mq: number;
}

// Queues (mockup 06): transport health from GET /api/v1/ops/queues. MQ depth is an
// application estimate (labeled as such, never a broker probe); a stale
// quarantine projection renders its last observation with an "unavailable"
// current count, never zero. The trend chart has no server history source, so it
// accumulates one sample per successful refresh and says "since page open".
@Component({
  selector: 'qd-queues',
  imports: [CommonModule, TimeAgoPipe, RefreshNoteComponent, StateMessageComponent, StatusBadgeComponent, TrendChartComponent],
  templateUrl: './queues.page.html',
})
export class QueuesPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);

  protected readonly backlog: BacklogSample[] = [];
  protected readonly view$: Observable<PollResult<QueuesView>> = this.polling
    .poll(() => this.api.getQueues())
    .pipe(
      tap((r) => {
        if (r.status === 'ok') {
          this.backlog.push({ at: r.at, outbox: r.value.outbox.pending, mq: r.value.mq.depthEstimate });
          if (this.backlog.length > 60) this.backlog.shift();
        }
      }),
    );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected trend(): TrendSeries[] {
    return [
      { label: 'Outbox pending', color: '#0369a1', values: this.backlog.map((s) => s.outbox) },
      { label: 'MQ depth (est.)', color: '#0d9488', values: this.backlog.map((s) => s.mq) },
    ];
  }

  protected formatAge(seconds: number): string {
    if (seconds < 1) return '<1s';
    if (seconds < 90) return `${Math.round(seconds)}s`;
    if (seconds < 5400) return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
    return `${Math.round(seconds / 3600)}h`;
  }
}
