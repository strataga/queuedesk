import { Injectable } from '@angular/core';
import {
  Observable,
  catchError,
  exhaustMap,
  fromEvent,
  map,
  of,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

/**
 * One poll emission. Errors are values, not stream deaths: a failed refresh
 * still carries the last good reading (and when it succeeded) so views can
 * say "showing last good reading" instead of going blank.
 */
export type PollResult<T> =
  | { status: 'ok'; value: T; at: number }
  | { status: 'error'; error: unknown; at: number; lastOk: T | null; lastOkAt: number | null };

/** Default refresh cadence for every polled panel: 5 seconds. */
export const POLL_INTERVAL_MS = 5000;

/**
 * Visibility-aware polling: fetch immediately, then on the interval while
 * the page is visible. Hidden tabs stop polling entirely and resume on
 * return, and an in-flight request is never overlapped by the next tick
 * (`exhaustMap`), so a slow backend cannot queue up duplicate work.
 */
@Injectable({ providedIn: 'root' })
export class PollingService {
  private readonly visibility$: Observable<boolean>;

  constructor() {
    const initial = typeof document === 'undefined' ? true : !document.hidden;
    this.visibility$ =
      typeof document === 'undefined'
        ? of(true)
        : fromEvent(document, 'visibilitychange').pipe(
            map(() => !document.hidden),
            startWith(initial),
          );
  }

  /**
   * Polls `fetch` on the interval while the page is visible. The
   * stream never errors and never completes; unsubscribing stops all work.
   * Each call creates an independent poll (cold), so pages subscribe once.
   */
  poll<T>(
    fetch: () => Observable<T>,
    intervalMs: number = POLL_INTERVAL_MS,
  ): Observable<PollResult<T>> {
    let lastOk: T | null = null;
    let lastOkAt: number | null = null;
    return this.visibility$.pipe(
      switchMap((visible) =>
        visible
          ? timer(0, intervalMs).pipe(
              exhaustMap(() =>
                fetch().pipe(
                  map((value): PollResult<T> => {
                    lastOk = value;
                    lastOkAt = Date.now();
                    return { status: 'ok', value, at: lastOkAt };
                  }),
                  catchError((error: unknown) =>
                    of<PollResult<T>>({ status: 'error', error, at: Date.now(), lastOk, lastOkAt }),
                  ),
                ),
              ),
            )
          : new Observable<PollResult<T>>(() => {}),
      ),
    );
  }

  /**
   * Turns an unknown failure into one user-facing sentence. Status 0 means
   * unreachable (not "HTTP 0"), 404 gets its own wording, and the backend's
   * stable error code is included when present. Never dumps raw objects.
   */
  describeError(error: unknown): string {
    if (error !== null && typeof error === 'object') {
      const maybe = error as { status?: unknown; error?: unknown; message?: unknown };
      if (typeof maybe.status === 'number') {
        if (maybe.status === 0) return 'Backend unreachable (connection refused or offline).';
        if (maybe.status === 404) return 'Not found: the API has no record with this identity.';
        const code = (maybe.error as { code?: unknown } | null)?.code;
        return `Request failed (HTTP ${maybe.status}${typeof code === 'string' ? `, ${code}` : ''}).`;
      }
      if (typeof maybe.message === 'string' && maybe.message.length > 0) return maybe.message;
    }
    return 'Request failed.';
  }
}
