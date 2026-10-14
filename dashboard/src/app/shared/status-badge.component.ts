import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

// Status pill (UI-06): every state carries a text label plus a marker glyph, so
// meaning never depends on color alone. Unknown tokens render verbatim with a "?"
// marker rather than silently mapping to a known style.
@Component({
  selector: 'qd-status-badge',
  imports: [CommonModule],
  template: `
    <span
      [attr.title]="label"
      class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium whitespace-nowrap"
      [ngClass]="tone"
    >
      <span aria-hidden="true" class="text-[0.7rem] leading-none">{{ marker }}</span>
      <span>{{ label }}</span>
    </span>
  `,
})
export class StatusBadgeComponent {
  @Input({ required: true }) value: string | null | undefined = null;
  @Input() kind: 'business' | 'delivery' | 'health' | 'fresh' | 'risk' = 'business';

  private static readonly BUSINESS: Record<
    string,
    { label: string; marker: string; tone: string }
  > = {
    RECEIVED: {
      label: 'Received',
      marker: '●',
      tone: 'border-slate-300 bg-slate-100 text-slate-800',
    },
    BUSINESS_ACCEPTED: {
      label: 'Accepted',
      marker: '◐',
      tone: 'border-sky-300 bg-sky-100 text-sky-900',
    },
    PENDING_CONFIRMATION: {
      label: 'Pending confirmation',
      marker: '◷',
      tone: 'border-amber-300 bg-amber-100 text-amber-900',
    },
    COMPLETED_SIMULATED: {
      label: 'Completed (simulated)',
      marker: '✔',
      tone: 'border-emerald-300 bg-emerald-100 text-emerald-900',
    },
    REJECTED: { label: 'Rejected', marker: '✕', tone: 'border-red-300 bg-red-100 text-red-900' },
    RISK_HELD: {
      label: 'Risk held',
      marker: '◉',
      tone: 'border-amber-300 bg-amber-100 text-amber-900',
    },
  };

  private static readonly DELIVERY: Record<
    string,
    { label: string; marker: string; tone: string }
  > = {
    PENDING_PUBLISH: {
      label: 'Pending publish',
      marker: '○',
      tone: 'border-slate-300 bg-slate-100 text-slate-800',
    },
    PUBLISHED: { label: 'Published', marker: '➤', tone: 'border-sky-300 bg-sky-100 text-sky-900' },
    RETRYING: {
      label: 'Retrying',
      marker: '↻',
      tone: 'border-amber-300 bg-amber-100 text-amber-900',
    },
    QUARANTINED: {
      label: 'Quarantined',
      marker: '⚠',
      tone: 'border-orange-300 bg-orange-100 text-orange-900',
    },
  };

  private static readonly HEALTH: Record<string, { label: string; marker: string; tone: string }> =
    {
      UP: { label: 'Up', marker: '✔', tone: 'border-emerald-300 bg-emerald-100 text-emerald-900' },
      DOWN: { label: 'Down', marker: '✕', tone: 'border-red-300 bg-red-100 text-red-900' },
      OUT_OF_SERVICE: {
        label: 'Out of service',
        marker: '■',
        tone: 'border-red-300 bg-red-100 text-red-900',
      },
      UNKNOWN: {
        label: 'Unknown',
        marker: '?',
        tone: 'border-slate-300 bg-slate-100 text-slate-800',
      },
    };

  private static readonly FRESH: Record<string, { label: string; marker: string; tone: string }> = {
    CURRENT: {
      label: 'Current',
      marker: '✔',
      tone: 'border-emerald-300 bg-emerald-100 text-emerald-900',
    },
    STALE: { label: 'Stale', marker: '◷', tone: 'border-amber-300 bg-amber-100 text-amber-900' },
    UNAVAILABLE: {
      label: 'Unavailable',
      marker: '✕',
      tone: 'border-red-300 bg-red-100 text-red-900',
    },
  };

  // Risk assessment outputs (mockups 01/02/04/05): assessment output explains a
  // hold; it is never a fraud determination. A null/unknown assessment renders
  // as "Not assessed" only when the backend said so — a missing value renders
  // verbatim via the unknown fallback below.
  private static readonly RISK: Record<string, { label: string; marker: string; tone: string }> = {
    ALLOW: {
      label: 'ALLOW',
      marker: '✔',
      tone: 'border-emerald-300 bg-emerald-100 text-emerald-900',
    },
    REVIEW: { label: 'REVIEW', marker: '◉', tone: 'border-amber-300 bg-amber-100 text-amber-900' },
    NOT_ASSESSED: {
      label: 'NOT_ASSESSED',
      marker: '◷',
      tone: 'border-slate-300 bg-slate-100 text-slate-800',
    },
    REJECT: { label: 'REJECT', marker: '✕', tone: 'border-red-300 bg-red-100 text-red-900' },
    PENDING: {
      label: 'Pending',
      marker: '○',
      tone: 'border-slate-300 bg-slate-100 text-slate-800',
    },
  };

  private entry(): { label: string; marker: string; tone: string } {
    const table =
      this.kind === 'delivery'
        ? StatusBadgeComponent.DELIVERY
        : this.kind === 'health'
          ? StatusBadgeComponent.HEALTH
          : this.kind === 'fresh'
            ? StatusBadgeComponent.FRESH
            : this.kind === 'risk'
              ? StatusBadgeComponent.RISK
              : StatusBadgeComponent.BUSINESS;
    const key = (this.value ?? '').toUpperCase();
    return (
      table[key] ?? {
        label: this.value ?? 'unknown',
        marker: '?',
        tone: 'border-slate-300 bg-slate-100 text-slate-800',
      }
    );
  }

  get label(): string {
    return this.entry().label;
  }

  get marker(): string {
    return this.entry().marker;
  }

  get tone(): string {
    return this.entry().tone;
  }
}
