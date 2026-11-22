# Workflow: live alert stream, browser to backend and back

This document describes one call end to end: how the console receives alert changes in real time.

**Design in one line:** Server-Sent Events from the backend to the browser, PostgreSQL NOTIFY and LISTEN between the instances (one implementation), a `fetch`-based client that sends the `Authorization` header, `SseEmitter` on the server, and a small event payload (section 5).

## What this feature is for

The alert panel shows "a live list of alerts with status, zone, asset and owner". When a new alert is raised, or another operator claims, releases or resolves one, every operator's panel updates without a reload. This stream carries those changes. It also supports three neighbouring requirements: showing when live updates are interrupted, becoming correct again after a 30-second network loss without a reload, and announcing new alerts to screen readers without flooding them.

It is not used for the map. Asset positions are polled about once a second with ordinary requests.

## Two different links

```
instance 1 ──NOTIFY──► PostgreSQL ──LISTEN──► instance 2     (between backends, through the database; no token)
instance 2 ──── live stream (SSE) ────► operator's browser   (the token matters here)
```

NOTIFY and LISTEN only carry the news between the backend instances. The token question is about the second link, from a backend to the browser.

## The picture

```
Browser (console)                nginx :8080             backend-A / backend-B                 PostgreSQL
─────────────────                ───────────             ─────────────────────                 ──────────
1 POST /auth/login ───────────────────────────────────►  check password, sign JWT
  ◄──────────────────────────────────────────────────── { accessToken, expiresInSec, user }
2 GET /alerts, /zones  (Authorization: Bearer ...)  ───►  normal REST reads
3 fetch GET /alerts/stream  (Authorization: Bearer ...) ► backend-B: check token, open SseEmitter
  ◄══ response stays open, events arrive as text ═════════  registry holds the emitter

            (elsewhere) ingest on backend-A creates an alert:
            txn: INSERT alert + audit, SELECT pg_notify('alerts', payload)  ───────────►  COMMIT
                                                           ◄── LISTEN: A and B both receive ──
            B: registry.broadcast → emitter.send(event)
  ◄══ event: alert.changed  data: {"type":"CLAIMED","alertId":"..."} ══
4 console updates the alert list, announces it, shows it in the panel
```

## 1. Sign-in and the token

- `POST /api/v1/auth/login` returns `accessToken`, `expiresInSec` and the user (name, role).
- The console keeps the token in memory. A page reload means signing in again. That is acceptable here and avoids storing the token in browser storage.
- Every API call, including the stream, sends `Authorization: Bearer <token>`.

## 2. Opening the stream (browser)

- After sign-in and the first loads (`GET /alerts`, `GET /zones`), the console calls `fetch('/api/v1/alerts/stream')` with the `Authorization` header and an `AbortController` signal, so the call can be cancelled.
- It reads `response.body` as a stream: decode the bytes as text, split into lines, collect the SSE fields (`event:`, `data:`). A blank line ends one event. This is about 30 lines of code. A small pinned library such as `@microsoft/fetch-event-source` does the same and is bundled at build time, so it works offline.
- The browser's built-in `EventSource` is not used, because it cannot send the header.
- This is one long-lived connection, not polling.

## 3. Accepting the stream (backend)

- A controller method `GET /api/v1/alerts/stream` returns an `SseEmitter` (Spring MVC). The same security check as the other endpoints applies. Allowed roles: OPERATOR and SUPERVISOR. A missing or bad token gets 401 and the INGEST role gets 403, both as problem+json before any stream starts.
- The emitter's timeout is the token's remaining lifetime, so the stream closes when the token expires.
- The emitter is registered in an in-memory `AlertStreamRegistry`, a thread-safe set. It holds only this instance's own connections. That is fine, because it is not shared state and no rule depends on it.
- On completion, timeout or error the emitter is removed from the registry.
- A `ready` event is sent immediately, so the console knows the stream is live.
- A scheduled task sends a comment line (`: ping`) to every emitter every 15 to 25 seconds. This keeps nginx and any idle connection from closing the stream, and lets the client detect a dead connection.
- An open `SseEmitter` does not tie up a request thread while idle, and with about 20 operators the load is negligible.

### Server-side alternatives to `SseEmitter`

| Alternative | What it is | Why it is second best |
|---|---|---|
| **`SseEmitter`** (chosen) | Spring MVC's class for server-sent events. The controller returns it and calls `send(...)` whenever there is an event. | Fits our stack: Spring MVC with blocking JDBC. Simple, well known, one class to explain. |
| **`Flux<ServerSentEvent>`** (WebFlux or Reactor) | The reactive way: the controller returns a stream of events. | Needs the reactive library and reactive thinking in one corner of a blocking application. More to explain, no benefit at about 20 operators. |
| **`StreamingResponseBody`** | Writes a long response by hand to the output stream. | Lower level. We would write the event format, timeouts and cleanup ourselves. |
| **WebSocket** (Spring WebSocket or STOMP) | A two-way channel. | Needs a handshake that carries the token, extra configuration, and a two-way channel we do not need. |
| **Long polling** | The client asks, the server holds the request until an event or a timeout. | Many reconnects and more code on both sides than a stream. |

## 4. Producing events (backend to backend)

- When the service changes an alert, on ingest (OPENED) or on a command (claim, release, resolve), it runs `SELECT pg_notify('alerts', :payload)` inside the same transaction. The payload is small: the event type, the alert id, the new status, and the asset and zone ids (which never change for an alert).
- PostgreSQL delivers the notification to every listener only after the transaction commits. A rolled-back change sends nothing.
- Each instance keeps one dedicated connection that runs `LISTEN alerts` and reads notifications in a loop. This connection is not taken from the normal pool.
- The listener hands each message to `AlertEventBus.publish`. The only implementation calls `registry.broadcast`, which sends `emitter.send(event().name("alert.changed").data(json))` to each emitter.
- Both instances receive the notification, including the one that created it, so each pushes to its own browsers.
- If sending to an emitter fails or blocks, that connection is closed and removed. The client reconnects and refetches, so one slow browser cannot hold up the others.
- Only low-volume events use NOTIFY. Never one per position or per ingest line.

## 5. What the console does with an event

**Event payload:** `{"type":"CLAIMED","alertId":"...","status":"CLAIMED","assetId":"...","zoneId":"..."}`. It does not carry the full alert. The console refetches the alert, `GET /alerts/{id}`, and writes the result into the cached alert list (TanStack Query).

**Why a small payload and a refetch, based on the requirements**
- **Always correct.** The refetch returns the current state of the alert, so a late or reordered event can never put an older state on screen. With the full alert in the payload, an event that predates the initial list load, or arrives behind a newer one, could overwrite newer data. Preventing that needs a version on every alert, which the fixed contract does not have.
- **Self-repairing.** The same rule (refetch on any event, refetch everything after a reconnect) covers missed events, which NOTIFY can produce. This supports the requirement to become correct after a 30-second network loss without a reload.
- **Small and safe.** The payload stays far below the notification size limit of about 8 KB, and it holds no owner name or note.
- **Enough for announcements.** The type, asset and zone ids are what a screen-reader announcement needs, such as "New alert: asset AST-5 entered zone Z-01", so it can be made without waiting for the refetch.

**The burst case.** The console must stay usable while the feed runs at maximum speed, so many alerts can arrive within seconds. To avoid a refetch storm, the console collects event ids for about 250 ms, removes duplicates, and then refetches each distinct alert. If more than about 20 distinct alerts changed in that window, it refetches the alert list pages in one go instead. Screen updates are applied once per animation frame.

**Announcements.** New alerts are announced through an `aria-live` region. Announcements are grouped and rate-limited, for example "3 new alerts" every few seconds, so a screen-reader user is not flooded.

**Alternative: the full alert in the payload.** One fewer request per event and the panel updates instantly, and an alert is about half a kilobyte, so it would fit. Not chosen because of the ordering risk above, and because the burst is already handled by batching the refetches.

## 6. Connection state and recovery

- Console states: connecting, live, reconnecting, signed out. The panel shows the state.
- If the stream ends, or no bytes (pings included) arrive for about 45 seconds, the console aborts and reconnects. Waits grow from 1 second up to about 15 seconds, with some random variation.
- On every successful reconnect, the console refetches the alert list and the current viewport, then shows live again. NOTIFY is not durable, so missed events are repaired this way. This also meets the requirement to become correct after a 30-second network loss without a page reload.
- As a safety net, the alert list is also refreshed every 30 to 60 seconds.

## 7. Token expiry

- The server closes the stream at token expiry. The reconnect attempt then gets 401. The contract has no refresh endpoint, so the console shows "session expired, sign in again" and stops retrying.

## 8. nginx settings for the stream path

- `proxy_http_version 1.1`, `proxy_buffering off`, `proxy_cache off`, and a long `proxy_read_timeout` (about an hour).
- The backend also sends `X-Accel-Buffering: no`. `X-Request-Id` is passed through.
- During the rolling upgrade, a backend leaving rotation ends its streams. Browsers reconnect to the other backend through nginx and refetch. Those are new requests that succeed, not failed ones.

## 9. Security and logging

- The token travels only in the header and is never logged. The stream endpoint logs the request id, the user name, and the open and close events, never the header.
- Cap the number of open streams (for example 200 in total) to protect an instance.

## 10. Alternatives for how the browser proves who it is

| Alternative | How it works | Why it is second best |
|---|---|---|
| **One-time ticket** | The console asks for a short-lived, single-use ticket using the normal token, then opens the stream with the ticket in the URL. | Works with `EventSource`, but adds an endpoint and ticket storage that both instances must share. |
| **Token in the URL** | The JWT goes in the query string. | URLs end up in logs, and the requirement says no tokens in logs. Rejected. |
| **Cookie** | Login also sets a cookie, and `EventSource` sends cookies automatically. | The contract's login returns JSON and the API uses bearer tokens. A cookie adds a second way to authenticate and needs protection against cross-site requests. |
| **WebSocket** | Send the token in the first message after connecting. | Needs a two-way channel we do not otherwise need. |
| **Poll the alerts** | The console asks for the alert list every second or two with a normal request and header. | No stream to authenticate and no NOTIFY needed, but it replaces the live-update decision (SSE plus NOTIFY) and alerts arrive up to a second or two later. |

## 11. Tests for this path

- An alert created through ingest on backend A reaches a stream open on backend B.
- A request with no token gets 401, and the INGEST role gets 403, with no stream opened.
- The stream closes at token expiry.
- After the LISTEN connection is dropped, the listener reconnects and events resume.
- A slow or dead client is removed without blocking the others.
- After a reconnect, the console's alert list and viewport are correct without a page reload.
