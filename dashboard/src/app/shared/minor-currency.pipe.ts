import { Pipe, PipeTransform } from '@angular/core';

// Integer minor-unit currency (UI-06): formats without floating point, so
// 12500 minor USD is always $125.00 and never $124.99(9). Unknown currencies
// fall back to "12500 XXX minor" rather than inventing a symbol.
@Pipe({ name: 'minorCurrency' })
export class MinorCurrencyPipe implements PipeTransform {
  transform(amountMinor: number | null | undefined, currency: string | null | undefined): string {
    if (amountMinor === null || amountMinor === undefined || !Number.isInteger(amountMinor)) {
      return 'unknown amount';
    }
    const negative = amountMinor < 0;
    const abs = Math.abs(amountMinor);
    const units = Math.trunc(abs / 100);
    const cents = String(abs % 100).padStart(2, '0');
    const grouped = units.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    if (currency === 'USD') return `${negative ? '-' : ''}$${grouped}.${cents}`;
    return `${negative ? '-' : ''}${grouped}.${cents} ${currency ?? 'unknown currency'} minor`;
  }
}
