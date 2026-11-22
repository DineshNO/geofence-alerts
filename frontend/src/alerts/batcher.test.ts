import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AlertEvent } from '../api/types';
import { EventBatcher } from './batcher';
import type { Flush } from './batcher';

function event(alertId: string, type: AlertEvent['type'] = 'CLAIMED'): AlertEvent {
  return { type, alertId, status: 'CLAIMED', assetId: 'A', zoneId: 'Z-01' };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('EventBatcher', () => {
  it('turns a burst into one flush with each alert once', () => {
    const flushes: Flush[] = [];
    const batcher = new EventBatcher((f) => flushes.push(f));
    batcher.add(event('7'));
    batcher.add(event('8'));
    batcher.add(event('7', 'RESOLVED'));
    expect(flushes).toHaveLength(0);

    vi.advanceTimersByTime(250);

    expect(flushes).toHaveLength(1);
    expect(flushes[0]?.alertIds).toEqual(['7', '8']);
    expect(flushes[0]?.events).toHaveLength(3);
    expect(flushes[0]?.overflow).toBe(false);
  });

  it('starts a new window after a flush', () => {
    const flushes: Flush[] = [];
    const batcher = new EventBatcher((f) => flushes.push(f));
    batcher.add(event('1'));
    vi.advanceTimersByTime(250);
    batcher.add(event('2'));
    vi.advanceTimersByTime(250);
    expect(flushes.map((f) => f.alertIds)).toEqual([['1'], ['2']]);
  });

  it('says overflow when more than twenty different alerts changed', () => {
    const flushes: Flush[] = [];
    const batcher = new EventBatcher((f) => flushes.push(f));
    for (let i = 1; i <= 21; i++) {
      batcher.add(event(String(i)));
    }
    vi.advanceTimersByTime(250);
    expect(flushes[0]?.overflow).toBe(true);

    for (let i = 1; i <= 20; i++) {
      batcher.add(event(String(i)));
    }
    vi.advanceTimersByTime(250);
    expect(flushes[1]?.overflow).toBe(false);
  });

  it('flushes nothing when stopped', () => {
    const flushes: Flush[] = [];
    const batcher = new EventBatcher((f) => flushes.push(f));
    batcher.add(event('1'));
    batcher.stop();
    vi.advanceTimersByTime(1000);
    expect(flushes).toHaveLength(0);
  });
});
