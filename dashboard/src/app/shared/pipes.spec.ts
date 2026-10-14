/**
 * Formatting pipes: minor-unit currency uses integer math and never
 * invents a symbol or value; relative time renders ages and guards bad
 * input as "unknown age".
 */
import { MinorCurrencyPipe } from './minor-currency.pipe';
import { TimeAgoPipe } from './time-ago.pipe';

describe('MinorCurrencyPipe', () => {
  const pipe = new MinorCurrencyPipe();

  it('formats minor units with integer math', () => {
    expect(pipe.transform(12500, 'USD')).toBe('$125.00');
    expect(pipe.transform(1, 'USD')).toBe('$0.01');
    expect(pipe.transform(123456789, 'USD')).toBe('$1,234,567.89');
    expect(pipe.transform(0, 'USD')).toBe('$0.00');
  });

  it('never invents a symbol or a value', () => {
    expect(pipe.transform(null, 'USD')).toBe('unknown amount');
    expect(pipe.transform(12.5, 'USD')).toBe('unknown amount');
    expect(pipe.transform(12500, 'EUR')).toBe('125.00 EUR minor');
  });
});

describe('TimeAgoPipe', () => {
  const pipe = new TimeAgoPipe();
  const now = Date.parse('2026-10-05T12:00:00Z');

  it('renders relative ages and guards bad input', () => {
    expect(pipe.transform('2026-10-05T11:59:58Z', now)).toBe('just now');
    expect(pipe.transform('2026-10-05T11:59:00Z', now)).toBe('1m ago');
    expect(pipe.transform('2026-10-05T10:00:00Z', now)).toBe('2h ago');
    expect(pipe.transform(null, now)).toBe('unknown age');
    expect(pipe.transform('not-a-time', now)).toBe('unknown age');
  });
});
