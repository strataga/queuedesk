import { Component, Input } from '@angular/core';
import { TimeAgoPipe } from './time-ago.pipe';
import { StatusBadgeComponent } from './status-badge.component';

/**
 * Freshness line under every polled panel: absolute UTC time (in the title
 * and datetime attributes) plus relative age, with an explicit stale badge
 * when the reading is older than the poll cadence should allow.
 */
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
  /** Epoch milliseconds of the last successful refresh (null before the first success). */
  @Input({ required: true }) at: number | null = null;
  /** When true, render the stale badge beside the timestamp. */
  @Input() stale = false;

  /** ISO-8601 form of `at` for the datetime/title attributes. */
  get iso(): string | null {
    return this.at === null ? null : new Date(this.at).toISOString();
  }
}
