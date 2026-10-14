import { Component, Input } from '@angular/core';
import { TimeAgoPipe } from './time-ago.pipe';
import { StatusBadgeComponent } from './status-badge.component';

// Freshness line under every polled panel: absolute UTC time plus relative age,
// with an explicit stale marker when the reading is older than two poll ticks.
@Component({
  selector: 'qd-refresh-note',
  imports: [TimeAgoPipe, StatusBadgeComponent],
  template: `
    <p class="mt-2 text-xs text-slate-600">
      Last refresh:
      <time [attr.datetime]="iso" [attr.title]="iso">{{ at | timeAgo }}</time>
      @if (stale) {
        <qd-status-badge value="STALE" kind="fresh" class="ml-2"></qd-status-badge>
      }
    </p>
  `,
})
export class RefreshNoteComponent {
  @Input({ required: true }) at: number | null = null;
  @Input() stale = false;

  get iso(): string | null {
    return this.at === null ? null : new Date(this.at).toISOString();
  }
}
