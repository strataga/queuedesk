import { Component, Input } from '@angular/core';

/**
 * The four non-data panel states in one component: loading, empty, error
 * ("showing last good reading"), and unavailable (nothing has succeeded
 * yet). Keeping them visually and textually distinct is the point — a
 * backend outage must never look like an empty result.
 */
@Component({
  selector: 'qd-state-message',
  template: `
    @if (state === 'loading') {
      <div
        role="status"
        aria-live="polite"
        class="rounded-md border border-slate-200 bg-white px-4 py-6 text-center text-sm text-slate-700"
      >
        <span
          aria-hidden="true"
          class="mr-2 inline-block h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700 align-[-2px]"
        ></span
        >Loading…
      </div>
    } @else if (state === 'empty') {
      <div
        class="rounded-md border border-dashed border-slate-300 bg-white px-4 py-6 text-center text-sm text-slate-700"
      >
        <p class="font-medium">No data</p>
        <p class="mt-1">{{ message || 'Nothing matches this query yet.' }}</p>
      </div>
    } @else if (state === 'unavailable') {
      <div
        role="alert"
        class="rounded-md border border-red-300 bg-red-50 px-4 py-6 text-center text-sm text-red-900"
      >
        <p class="font-medium">✕ Backend unavailable</p>
        <p class="mt-1">{{ message || 'No reading has succeeded yet.' }}</p>
      </div>
    } @else {
      <div
        role="alert"
        class="rounded-md border border-amber-300 bg-amber-50 px-4 py-6 text-center text-sm text-amber-900"
      >
        <p class="font-medium">⚠ Showing last good reading</p>
        <p class="mt-1">{{ message || 'The latest refresh failed.' }}</p>
      </div>
    }
  `,
})
export class StateMessageComponent {
  /**
   * Which non-data state to render: `error` means "last good reading is
   * shown but refresh is failing"; `unavailable` means nothing has
   * succeeded yet. The distinction is user-visible on purpose.
   */
  @Input() state: 'loading' | 'empty' | 'error' | 'unavailable' = 'loading';
  /** Optional detail line; each state has an honest default. */
  @Input() message: string | null = null;
}
