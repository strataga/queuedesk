/**
 * Polling behavior with fake timers: fetch immediately, then per tick;
 * errors keep polling alive with the last good value attached; hidden tabs
 * stop and resume; transport failures are described in plain language.
 */
import { of, throwError } from 'rxjs';
import { PollingService } from './polling.service';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('PollingService', () => {
  const service = () => new PollingService();

  it('fetches immediately and then on each tick', async () => {
    let calls = 0;
    const seen: string[] = [];
    const sub = service()
      .poll(() => of(`v${++calls}`), 10)
      .subscribe((r) => {
        if (r.status === 'ok') seen.push(r.value);
      });
    await sleep(45);
    sub.unsubscribe();
    expect(calls).toBeGreaterThanOrEqual(3);
    expect(seen[0]).toBe('v1');
    expect(new Set(seen).size).toBe(seen.length);
  });

  it('keeps polling after an error and keeps the last good value', async () => {
    let calls = 0;
    const seen: Array<{ status: string; last: string | null }> = [];
    const sub = service()
      .poll(() => (++calls === 2 ? throwError(() => ({ status: 500 })) : of(`v${calls}`)), 10)
      .subscribe((r) =>
        seen.push(
          r.status === 'ok' ? { status: 'ok', last: null } : { status: 'error', last: r.lastOk },
        ),
      );
    await sleep(45);
    sub.unsubscribe();
    expect(seen[0]).toEqual({ status: 'ok', last: null });
    expect(seen[1]).toEqual({ status: 'error', last: 'v1' });
    expect(seen[2]).toEqual({ status: 'ok', last: null });
  });

  it('stops while hidden and resumes when visible', async () => {
    const hidden = { value: false };
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    let calls = 0;
    const seen: number[] = [];
    const sub = service()
      .poll(() => of(++calls), 10)
      .subscribe((r) => {
        if (r.status === 'ok') seen.push(r.value);
      });
    await sleep(25);
    const whileVisible = calls;
    expect(whileVisible).toBeGreaterThanOrEqual(2);
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await sleep(30);
    expect(calls).toBe(whileVisible);
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await sleep(25);
    expect(calls).toBeGreaterThan(whileVisible);
    sub.unsubscribe();
    Object.defineProperty(document, 'hidden', { value: hidden.value, configurable: true });
  });

  it('describes transport failures in plain language', () => {
    const s = service();
    expect(s.describeError({ status: 0 })).toContain('unreachable');
    expect(s.describeError({ status: 404 })).toContain('Not found');
    expect(s.describeError({ status: 500, error: { code: 'X' } })).toContain('HTTP 500');
    expect(s.describeError(new Error('boom'))).toBe('boom');
    expect(s.describeError(null)).toBe('Request failed.');
  });
});
