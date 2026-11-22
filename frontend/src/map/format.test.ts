import { describe, expect, it } from 'vitest';
import { formatCoordinate, formatNumber, formatTime, minutesBefore } from './format';

describe('format', () => {
  it('shows times in UTC without milliseconds', () => {
    expect(formatTime('2026-11-02T06:00:05.120Z')).toBe('2026-11-02 06:00:05 UTC');
    expect(formatTime(undefined)).toBe('–');
  });

  it('shows coordinates with five decimals and missing numbers as a dash', () => {
    expect(formatCoordinate(35.4)).toBe('35.40000');
    expect(formatNumber(14.2, 'kn')).toBe('14.2 kn');
    expect(formatNumber(null, 'kn')).toBe('–');
  });

  it('starts the track window ten minutes back, rounded down to the minute', () => {
    expect(minutesBefore('2026-11-02T06:10:45.000Z', 10)).toBe('2026-11-02T06:00:00.000Z');
  });
});
