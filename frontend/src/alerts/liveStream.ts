import type { AlertEvent } from '../api/types';
import { SseParser } from './sse';

export type ConnectionState = 'connecting' | 'live' | 'reconnecting';

export interface StreamHandlers {
  onState(state: ConnectionState): void;
  onEvent(event: AlertEvent): void;
  /** Events may have been missed (after a reconnect, or the server said so): refetch. */
  onResync(): void;
  /** The token is no longer accepted: the session is over. */
  onUnauthorized(): void;
}

export const STREAM_PATH = '/api/v1/alerts/stream';
/** No bytes (not even a heartbeat) for this long means the connection is dead. */
export const SILENCE_LIMIT_MS = 45_000;
const BASE_DELAY_MS = 1_000;
const MAX_DELAY_MS = 15_000;

/** 1 s, 2 s, 4 s ... up to 15 s, with up to a quarter more at random so clients do not line up. */
export function reconnectDelay(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** attempt);
  return Math.round(base + base * 0.25 * random());
}

/**
 * Keeps the live stream of alert changes open. It is one long-lived request, not polling. After
 * every (re)connect it asks the console to refetch, because notifications are not stored: a change
 * made while the stream was down would otherwise be missed.
 * Returns a function that stops it.
 */
export function startAlertStream(
  token: string,
  handlers: StreamHandlers,
  fetchImpl: typeof fetch = (...args) => fetch(...args),
): () => void {
  let stopped = false;
  let current: AbortController | null = null;
  let sleepTimer: ReturnType<typeof setTimeout> | null = null;

  async function run() {
    let attempt = 0;
    let first = true;
    while (!stopped) {
      handlers.onState(first ? 'connecting' : 'reconnecting');
      const controller = new AbortController();
      current = controller;
      let watchdog: ReturnType<typeof setTimeout> | null = null;
      const arm = () => {
        if (watchdog !== null) {
          clearTimeout(watchdog);
        }
        watchdog = setTimeout(() => controller.abort(), SILENCE_LIMIT_MS);
      };
      try {
        arm();
        const response = await fetchImpl(STREAM_PATH, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (response.status === 401) {
          handlers.onUnauthorized();
          return;
        }
        if (!response.ok || !response.body) {
          throw new Error(`The stream answered ${response.status}.`);
        }
        attempt = 0;
        first = false;
        handlers.onState('live');
        handlers.onResync();
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parser = new SseParser();
        for (;;) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          arm();
          for (const message of parser.push(decoder.decode(value, { stream: true }))) {
            dispatch(message.event, message.data);
          }
        }
      } catch {
        // the connection failed, was cut, or was silent for too long: try again below
      } finally {
        if (watchdog !== null) {
          clearTimeout(watchdog);
        }
      }
      if (stopped) {
        return;
      }
      handlers.onState('reconnecting');
      await new Promise<void>((resolve) => {
        sleepTimer = setTimeout(resolve, reconnectDelay(attempt));
      });
      attempt += 1;
    }
  }

  function dispatch(event: string, data: string) {
    if (event === 'alert.changed') {
      try {
        handlers.onEvent(JSON.parse(data) as AlertEvent);
      } catch {
        // an unreadable event is skipped; the next refetch repairs the screen
      }
    } else if (event === 'resync') {
      handlers.onResync();
    }
  }

  void run();
  return () => {
    stopped = true;
    current?.abort();
    if (sleepTimer !== null) {
      clearTimeout(sleepTimer);
    }
  };
}
