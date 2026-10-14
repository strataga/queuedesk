import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Guards, Health, MetricValue, QuarantineView } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

interface MetricRow {
  label: string;
  name: string;
  tag?: { key: string; value: string };
  format: 'count' | 'seconds';
}

const METRICS: readonly MetricRow[] = [
  { label: 'Outbox pending', name: 'queuedesk.outbox.pending', format: 'count' },
  { label: 'Outbox oldest age', name: 'queuedesk.outbox.oldest.age.seconds', format: 'seconds' },
  { label: 'Pending confirmations', name: 'queuedesk.pending.confirmation.count', format: 'count' },
  {
    label: 'Pending oldest age',
    name: 'queuedesk.pending.confirmation.oldest.age.seconds',
    format: 'seconds',
  },
  { label: 'Quarantined events', name: 'queuedesk.quarantine.count', format: 'count' },
  {
    label: 'Quarantine projection age',
    name: 'queuedesk.quarantine.freshness.age.seconds',
    format: 'seconds',
  },
  { label: 'MQ backlog estimate', name: 'queuedesk.mq.backlog.estimate.count', format: 'count' },
  {
    label: 'MQ backlog oldest age',
    name: 'queuedesk.mq.backlog.estimate.oldest.age.seconds',
    format: 'seconds',
  },
  {
    label: 'Admissions accepted',
    name: 'queuedesk.admissions.total',
    tag: { key: 'decision', value: 'accepted' },
    format: 'count',
  },
  {
    label: 'Admissions repeated',
    name: 'queuedesk.admissions.total',
    tag: { key: 'decision', value: 'repeated' },
    format: 'count',
  },
  {
    label: 'Admissions invalid',
    name: 'queuedesk.admissions.total',
    tag: { key: 'decision', value: 'invalid' },
    format: 'count',
  },
  {
    label: 'Outcomes completed',
    name: 'queuedesk.outcomes.total',
    tag: { key: 'result', value: 'COMPLETED_SIMULATED' },
    format: 'count',
  },
  {
    label: 'Outcomes rejected',
    name: 'queuedesk.outcomes.total',
    tag: { key: 'result', value: 'REJECTED' },
    format: 'count',
  },
  { label: 'Redeliveries absorbed', name: 'queuedesk.dedup.total', format: 'count' },
  { label: 'Tasks reconciled', name: 'queuedesk.reconciled.total', format: 'count' },
  {
    label: 'Telemetry spans failed',
    name: 'queuedesk.telemetry.export.spans.failed',
    format: 'count',
  },
  { label: 'Telemetry dropped', name: 'queuedesk.telemetry.buffer.dropped', format: 'count' },
  {
    label: 'Risk holds (REVIEW)',
    name: 'queuedesk.risk.holds.total',
    tag: { key: 'result', value: 'REVIEW' },
    format: 'count',
  },
  {
    label: 'Risk holds (NOT_ASSESSED)',
    name: 'queuedesk.risk.holds.total',
    tag: { key: 'result', value: 'NOT_ASSESSED' },
    format: 'count',
  },
  {
    label: 'Risk provider calls (OK)',
    name: 'queuedesk.risk.provider.calls.total',
    tag: { key: 'status', value: 'OK' },
    format: 'count',
  },
  {
    label: 'Risk provider calls (TIMEOUT)',
    name: 'queuedesk.risk.provider.calls.total',
    tag: { key: 'status', value: 'TIMEOUT' },
    format: 'count',
  },
];

interface OpsSnapshot {
  guards: Guards;
  liveness: Health;
  readiness: Health;
  quarantine: QuarantineView;
  metrics: MetricValue[];
}

// Operations (UI-05): backlog, MQ availability, quarantine freshness and safeguard
// state with distinct loading/empty/unavailable/stale presentations. Metric rows
// with no reading say "no reading" — a series that never recorded is not zero.
@Component({
  selector: 'qd-ops',
  imports: [
    CommonModule,
    RouterLink,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './ops.page.html',
})
export class OpsPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);

  protected readonly metricRows = METRICS;
  protected readonly view$: Observable<PollResult<OpsSnapshot>> = this.polling.poll(() =>
    forkJoin({
      guards: this.api.getGuards(),
      liveness: this.api.getHealth('liveness'),
      readiness: this.api.getHealth('readiness'),
      quarantine: this.api.getQuarantine(),
      metrics: forkJoin(METRICS.map((m) => this.api.getMetric(m.name, m.tag))),
    }),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected formatMetric(value: number | null, format: 'count' | 'seconds'): string {
    if (value === null || Number.isNaN(value)) return 'no reading';
    if (format === 'seconds') {
      if (value < 1) return '<1s';
      if (value < 90) return `${Math.round(value)}s`;
      if (value < 5400) return `${Math.round(value / 60)}m`;
      return `${Math.round(value / 3600)}h`;
    }
    return Math.round(value).toLocaleString('en-US');
  }

  protected formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let scaled = bytes / 1024;
    let unit = units[0];
    for (const candidate of units) {
      unit = candidate;
      if (scaled < 1024 || candidate === 'TB') break;
      scaled /= 1024;
    }
    return `${scaled.toFixed(1)} ${unit}`;
  }

  protected healthEntries(health: Health): Array<{ name: string; status: string }> {
    return Object.entries(health.components ?? {}).map(([name, component]) => ({
      name,
      status: component.status,
    }));
  }
}
