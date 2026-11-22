import type { Alert, AlertStatus } from '../api/types';

/**
 * Puts a changed alert into the cached list of one status: it is removed from the list if it is
 * there, then added back (in id order, oldest first like the API) when its status is this one.
 * Moving an alert between lists is therefore the same call on each list.
 */
export function mergeIntoList(list: readonly Alert[], alert: Alert, listStatus: AlertStatus): Alert[] {
  const without = list.filter((a) => a.alertId !== alert.alertId);
  if (alert.status !== listStatus) {
    return without;
  }
  const merged = [...without, alert];
  merged.sort((a, b) => Number(a.alertId) - Number(b.alertId));
  return merged;
}
