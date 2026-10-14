import { Pipe, PipeTransform } from '@angular/core';

// Relative age for timestamps ("3m ago"). Invalid input renders as "unknown age"
// instead of NaN text; callers keep the absolute UTC time in the title attribute.
@Pipe({ name: 'timeAgo' })
export class TimeAgoPipe implements PipeTransform {
  transform(value: string | number | null | undefined, now: number = Date.now()): string {
    if (value === null || value === undefined) return 'unknown age';
    const at = typeof value === 'number' ? value : Date.parse(value);
    if (Number.isNaN(at)) return 'unknown age';
    const seconds = Math.max(0, Math.floor((now - at) / 1000));
    if (seconds < 5) return 'just now';
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 48) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }
}
