import { PollResult } from './polling.service';

// Normalized polled view: every async panel renders from one shape — a value that
// may be fresh or stale, plus the refresh instant and the latest failure if any.
export interface PollView<T> {
  value: T | null;
  fresh: boolean;
  at: number | null;
  error: unknown;
}

export function toView<T>(result: PollResult<T>): PollView<T> {
  return result.status === 'ok'
    ? { value: result.value, fresh: true, at: result.at, error: null }
    : { value: result.lastOk, fresh: false, at: result.lastOkAt, error: result.error };
}
