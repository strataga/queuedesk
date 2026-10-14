import { PollResult } from './polling.service';

/**
 * Normalized polled view: every async panel renders from this one shape —
 * a value that may be fresh or stale, the instant it was last refreshed,
 * and the latest failure if any. `fresh === false` with a non-null
 * value means "showing last good reading".
 */
export interface PollView<T> {
  value: T | null;
  fresh: boolean;
  at: number | null;
  error: unknown;
}

/** Maps a poll emission to the shared view shape (stale values keep the last-good timestamp). */
export function toView<T>(result: PollResult<T>): PollView<T> {
  return result.status === 'ok'
    ? { value: result.value, fresh: true, at: result.at, error: null }
    : { value: result.lastOk, fresh: false, at: result.lastOkAt, error: result.error };
}
