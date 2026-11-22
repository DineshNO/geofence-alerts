import { describe, expect, it } from 'vitest';
import type { Alert } from '../api/types';
import { mergeIntoList } from './cache';

function alert(id: string, status: Alert['status']): Alert {
  return {
    alertId: id,
    assetId: 'A',
    zoneId: 'Z-01',
    status,
    openedAt: '2026-11-02T06:00:00.000Z',
    triggerReportId: 'r',
  };
}

describe('mergeIntoList', () => {
  it('adds an alert to the list of its status, in id order', () => {
    const list = [alert('2', 'OPEN'), alert('10', 'OPEN')];
    const merged = mergeIntoList(list, alert('9', 'OPEN'), 'OPEN');
    expect(merged.map((a) => a.alertId)).toEqual(['2', '9', '10']); // numeric, not text, order
  });

  it('takes the alert out of the lists of other statuses', () => {
    const claimed = alert('5', 'CLAIMED');
    expect(mergeIntoList([alert('5', 'OPEN'), alert('6', 'OPEN')], claimed, 'OPEN').map((a) => a.alertId)).toEqual(['6']);
    expect(mergeIntoList([], claimed, 'CLAIMED')).toEqual([claimed]);
  });

  it('replaces an alert that is already in the list', () => {
    const newer = { ...alert('5', 'CLAIMED'), claimedBy: 'op01' };
    const merged = mergeIntoList([alert('5', 'CLAIMED')], newer, 'CLAIMED');
    expect(merged).toHaveLength(1);
    expect(merged[0]?.claimedBy).toBe('op01');
  });

  it('does not change the list it was given', () => {
    const list = [alert('1', 'OPEN')];
    mergeIntoList(list, alert('2', 'OPEN'), 'OPEN');
    expect(list).toHaveLength(1);
  });
});
