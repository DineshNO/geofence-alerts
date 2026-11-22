import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { apiFetch } from '../api/client';
import type { Alert, AlertEvent } from '../api/types';
import { useSession } from '../state/session';
import { EventBatcher } from './batcher';
import { startAlertStream } from './liveStream';
import type { ConnectionState } from './liveStream';
import { mergeAlertEverywhere } from './queries';

const OVERFLOW_REFETCH_EVERY_MS = 2_000;

export interface LiveAlerts {
  state: ConnectionState;
  /** When the stream was lost, while it is down. */
  interruptedSince: number | null;
}

/**
 * Keeps the alert lists current from the live stream. A burst of events is grouped for about a
 * quarter of a second; each changed alert is then refetched once (the answer is its true current
 * state, so a late or reordered event can never show an older state), or, when more than twenty
 * changed, the lists are refetched, at most every two seconds. After every reconnect the lists are
 * refetched, so the screen is correct again without a page reload.
 */
export function useLiveAlerts(onOpened: (events: AlertEvent[]) => void): LiveAlerts {
  const { session, expire } = useSession();
  const token = session?.token ?? null;
  const queryClient = useQueryClient();
  const [state, setState] = useState<ConnectionState>('connecting');
  const [interruptedSince, setInterruptedSince] = useState<number | null>(null);
  const opened = useRef(onOpened);
  opened.current = onOpened;

  useEffect(() => {
    if (!token) {
      return undefined;
    }
    let lastOverflow = 0;
    let overflowTimer: ReturnType<typeof setTimeout> | null = null;

    const refetchLists = () => {
      void queryClient.invalidateQueries({ queryKey: ['alerts'] });
      void queryClient.invalidateQueries({ queryKey: ['occupancy'] });
    };

    const batcher = new EventBatcher((flush) => {
      const fresh = flush.events.filter((e) => e.type === 'OPENED');
      if (fresh.length > 0) {
        opened.current(fresh);
      }
      if (flush.overflow) {
        const wait = Math.max(0, lastOverflow + OVERFLOW_REFETCH_EVERY_MS - Date.now());
        if (overflowTimer === null) {
          overflowTimer = setTimeout(() => {
            overflowTimer = null;
            lastOverflow = Date.now();
            refetchLists();
          }, wait);
        }
        return;
      }
      for (const id of flush.alertIds) {
        apiFetch<Alert>(`/api/v1/alerts/${encodeURIComponent(id)}`, { token })
          .then((alert) => mergeAlertEverywhere(queryClient, alert))
          .catch(() => undefined); // the next refetch repairs the screen
      }
    });

    const stop = startAlertStream(token, {
      onState: (next) => {
        setState(next);
        setInterruptedSince((since) => (next === 'live' ? null : (since ?? Date.now())));
      },
      onEvent: (event) => batcher.add(event),
      onResync: refetchLists,
      onUnauthorized: () => expire('Your session expired. Please sign in again.'),
    });
    return () => {
      stop();
      batcher.stop();
      if (overflowTimer !== null) {
        clearTimeout(overflowTimer);
      }
    };
  }, [token, queryClient, expire]);

  return { state, interruptedSince };
}
