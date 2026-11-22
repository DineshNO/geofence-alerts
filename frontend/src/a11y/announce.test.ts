import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AlertEvent } from '../api/types';
import { AnnouncementQueue, describeNewAlerts } from './announce';

function opened(assetId: string, zoneId = 'Z-01'): AlertEvent {
  return { type: 'OPENED', alertId: assetId, status: 'OPEN', assetId, zoneId };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('describeNewAlerts', () => {
  it('tells the details of one alert and only the count of many', () => {
    expect(describeNewAlerts([opened('AST-1', 'Z-03')])).toBe('New alert: asset AST-1 entered zone Z-03.');
    expect(describeNewAlerts([opened('A'), opened('B'), opened('C')])).toBe('3 new alerts.');
    expect(describeNewAlerts([])).toBe('');
  });
});

describe('AnnouncementQueue', () => {
  it('announces the first alert at once', () => {
    const said: string[] = [];
    const queue = new AnnouncementQueue((t) => said.push(t));
    queue.addOpened([opened('AST-1')]);
    vi.advanceTimersByTime(1);
    expect(said).toEqual(['New alert: asset AST-1 entered zone Z-01.']);
  });

  it('puts a burst in one sentence and waits between announcements', () => {
    const said: string[] = [];
    const queue = new AnnouncementQueue((t) => said.push(t));
    queue.addOpened([opened('A')]);
    vi.advanceTimersByTime(1);
    expect(said).toHaveLength(1);

    // 37 more alerts in the next second: nothing is said yet
    for (let i = 0; i < 37; i++) {
      queue.addOpened([opened(`B${i}`)]);
    }
    vi.advanceTimersByTime(1000);
    expect(said).toHaveLength(1);

    // after the interval they are one announcement
    vi.advanceTimersByTime(3500);
    expect(said).toEqual(['New alert: asset A entered zone Z-01.', '37 new alerts.']);
  });

  it('says a connection message together with the alerts of the same moment', () => {
    const said: string[] = [];
    const queue = new AnnouncementQueue((t) => said.push(t));
    queue.addText('Live updates are back.');
    queue.addOpened([opened('A'), opened('B')]);
    vi.advanceTimersByTime(1);
    expect(said).toEqual(['Live updates are back. 2 new alerts.']);
  });

  it('says nothing when stopped', () => {
    const said: string[] = [];
    const queue = new AnnouncementQueue((t) => said.push(t));
    queue.addOpened([opened('A')]);
    queue.stop();
    vi.advanceTimersByTime(10_000);
    expect(said).toEqual([]);
  });
});
