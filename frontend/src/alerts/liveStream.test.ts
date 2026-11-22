import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AlertEvent } from '../api/types';
import { SILENCE_LIMIT_MS, reconnectDelay, startAlertStream } from './liveStream';
import type { ConnectionState } from './liveStream';

interface Stream {
  push(text: string): void;
  end(): void;
}

/** A fake server: each call of fetch gets a response whose body the test writes to. */
function fakeServer() {
  const streams: Stream[] = [];
  const calls: { token: string | null }[] = [];
  let nextStatus = 200;
  const fetchImpl = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    calls.push({ token: headers.get('Authorization') });
    if (nextStatus !== 200) {
      return Promise.resolve(new Response('', { status: nextStatus }));
    }
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start: (c) => (controller = c) });
    init?.signal?.addEventListener('abort', () => {
      try {
        controller.error(new DOMException('Aborted', 'AbortError'));
      } catch {
        // already closed
      }
    });
    const encoder = new TextEncoder();
    streams.push({
      push: (text) => controller.enqueue(encoder.encode(text)),
      end: () => controller.close(),
    });
    return Promise.resolve(new Response(body, { status: 200 }));
  });
  return {
    fetchImpl: fetchImpl as unknown as typeof fetch,
    streams,
    calls,
    answerWith(status: number) {
      nextStatus = status;
    },
  };
}

function recorder() {
  const states: ConnectionState[] = [];
  const events: AlertEvent[] = [];
  const log = { resyncs: 0, unauthorized: 0 };
  return {
    states,
    events,
    log,
    handlers: {
      onState: (s: ConnectionState) => states.push(s),
      onEvent: (e: AlertEvent) => events.push(e),
      onResync: () => (log.resyncs += 1),
      onUnauthorized: () => (log.unauthorized += 1),
    },
  };
}

const EVENT = '{"type":"CLAIMED","alertId":"7","status":"CLAIMED","assetId":"A1","zoneId":"Z-01"}';

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => vi.useRealTimers());

describe('startAlertStream', () => {
  it('sends the token in the Authorization header, goes live and asks for a refetch', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('tok123', rec.handlers, server.fetchImpl);

    await vi.waitFor(() => expect(rec.states).toEqual(['connecting', 'live']));
    expect(server.calls[0]?.token).toBe('Bearer tok123');
    expect(rec.log.resyncs).toBe(1);
    stop();
  });

  it('passes on each alert event, skipping the ready event and heartbeats', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));

    server.streams[0]?.push('event:ready\ndata:{}\n\n: ping\n\nevent:alert.changed\ndata:' + EVENT + '\n\n');
    await vi.waitFor(() => expect(rec.events).toHaveLength(1));
    expect(rec.events[0]?.alertId).toBe('7');
    expect(rec.events[0]?.type).toBe('CLAIMED');
    stop();
  });

  it('asks for a refetch when the server says events may have been missed', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));
    const before = rec.log.resyncs;
    server.streams[0]?.push('event:resync\ndata:{}\n\n');
    await vi.waitFor(() => expect(rec.log.resyncs).toBe(before + 1));
    stop();
  });

  it('shows reconnecting when the stream ends, reconnects, and refetches again', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));

    server.streams[0]?.end();
    await vi.waitFor(() => expect(rec.states).toContain('reconnecting'));

    await vi.advanceTimersByTimeAsync(1500);
    await vi.waitFor(() => expect(server.streams).toHaveLength(2));
    await vi.waitFor(() => expect(rec.states.at(-1)).toBe('live'));
    expect(rec.log.resyncs).toBe(2);
    stop();
  });

  it('gives up the connection when nothing, not even a heartbeat, arrives for 45 seconds', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));

    await vi.advanceTimersByTimeAsync(SILENCE_LIMIT_MS + 100);
    await vi.waitFor(() => expect(rec.states).toContain('reconnecting'));
    await vi.advanceTimersByTimeAsync(1500);
    await vi.waitFor(() => expect(server.streams).toHaveLength(2));
    stop();
  });

  it('stays connected while heartbeats keep arriving', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));

    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(SILENCE_LIMIT_MS - 5_000);
      server.streams[0]?.push(': ping\n\n');
    }
    expect(server.streams).toHaveLength(1);
    expect(rec.states.at(-1)).toBe('live');
    stop();
  });

  it('ends the session and does not retry when the token is refused', async () => {
    const server = fakeServer();
    server.answerWith(401);
    const rec = recorder();
    startAlertStream('expired', rec.handlers, server.fetchImpl);

    await vi.waitFor(() => expect(rec.log.unauthorized).toBe(1));
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(1);
  });

  it('keeps trying with a delay when the server answers with an error', async () => {
    const server = fakeServer();
    server.answerWith(503);
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);

    await vi.waitFor(() => expect(server.calls).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(1500);
    await vi.waitFor(() => expect(server.calls.length).toBeGreaterThanOrEqual(2));
    expect(rec.states).not.toContain('live');
    stop();
  });

  it('stops for good when asked', async () => {
    const server = fakeServer();
    const rec = recorder();
    const stop = startAlertStream('t', rec.handlers, server.fetchImpl);
    await vi.waitFor(() => expect(server.streams).toHaveLength(1));

    stop();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.streams).toHaveLength(1);
  });
});

describe('reconnectDelay', () => {
  it('doubles from one second up to fifteen', () => {
    expect([0, 1, 2, 3, 4, 5, 10].map((n) => reconnectDelay(n, () => 0))).toEqual([
      1000, 2000, 4000, 8000, 15000, 15000, 15000,
    ]);
  });

  it('adds up to a quarter at random', () => {
    expect(reconnectDelay(0, () => 1)).toBe(1250);
    expect(reconnectDelay(10, () => 1)).toBe(18750);
  });
});
