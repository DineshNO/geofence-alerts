import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { Alert, AlertPage, AlertStatus } from '../api/types';
import { useSignedIn } from '../state/session';
import { mergeIntoList } from './cache';

export const ALERT_STATUSES: AlertStatus[] = ['OPEN', 'CLAIMED', 'RESOLVED'];
const PAGE_SIZE = 500;
const MAX_PAGES = 5;

/** Follows the cursors until the end (bounded), so the list holds every alert of a status. */
async function fetchAllAlerts(token: string, status: AlertStatus, signal: AbortSignal): Promise<Alert[]> {
  const all: Alert[] = [];
  let cursor: string | null = null;
  for (let page = 0; page < MAX_PAGES; page++) {
    const query: string = `status=${status}&limit=${PAGE_SIZE}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const result: AlertPage = await apiFetch<AlertPage>(`/api/v1/alerts?${query}`, { token, signal });
    all.push(...result.items);
    if (!result.nextCursor) {
      break;
    }
    cursor = result.nextCursor;
  }
  return all;
}

/**
 * The alerts of one status, oldest first. They are also refreshed on a slow timer: the live stream
 * is the fast path, and this is the safety net for an event that was missed.
 */
export function useAlertList(status: AlertStatus, enabled = true) {
  const { token } = useSignedIn();
  return useQuery({
    queryKey: ['alerts', status],
    queryFn: ({ signal }) => fetchAllAlerts(token, status, signal),
    enabled,
    refetchInterval: 45_000,
  });
}

/** Puts a changed alert into every cached list: out of the lists it left, into the one it joined. */
export function mergeAlertEverywhere(queryClient: QueryClient, alert: Alert) {
  for (const status of ALERT_STATUSES) {
    queryClient.setQueryData<Alert[]>(['alerts', status], (old) =>
      old ? mergeIntoList(old, alert, status) : old,
    );
  }
}

export type Command = 'claim' | 'release' | 'resolve';

/** The operator commands. The answer is the alert as it is now, which is put straight on screen. */
export function useAlertCommands() {
  const { token } = useSignedIn();
  const queryClient = useQueryClient();

  async function run(alertId: string, command: Command, note?: string): Promise<Alert> {
    const alert = await apiFetch<Alert>(`/api/v1/alerts/${alertId}/${command}`, {
      method: 'POST',
      token,
      body: command === 'resolve' ? { note } : undefined,
    });
    mergeAlertEverywhere(queryClient, alert);
    return alert;
  }

  /** After a refused command: shows the alert as it really is now. */
  async function refresh(alertId: string): Promise<void> {
    try {
      mergeAlertEverywhere(queryClient, await apiFetch<Alert>(`/api/v1/alerts/${alertId}`, { token }));
    } catch {
      // the next refetch repairs the screen
    }
  }

  return { run, refresh };
}
