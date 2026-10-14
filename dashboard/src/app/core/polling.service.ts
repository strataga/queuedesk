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

export type PollResult<T> =
  | { status: 'ok'; value: T; at: number }
  | { status: 'error'; error: unknown; at: number; lastOk: T | null; lastOkAt: number | null };

export const POLL_INTERVAL_MS = 5000;

// Visibility-aware polling (UI-05): fetch immediately, then every 5s while the page
// is visible. Hidden tabs stop polling entirely and resume on return; an in-flight
// request is never overlapped by the next tick. Errors are values, not stream
// deaths: the last good reading stays available so views can say "stale".
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
