# Measurements

All numbers come from the full linux/amd64 stack installed from the offline bundle
(two backends, nginx load balancer, PostgreSQL 16) running on a developer Mac under
Docker Desktop with Rosetta emulation. That is slower than the target Linux machine, so
read these as a floor, not a promise.

## Ingest (full feed, 225,748 lines, 4 parallel connections, maximum speed, through the load balancer)

| Run | Result | Time | Throughput |
|---|---|---|---|
| Before the deadlock fix | 1 failed attempt, retried | 52.1 s | 4,333 lines/s |
| After the deadlock fix | 0 errors, 0 retries | 11.4 s | 19,750 lines/s |

First delivery: accepted 215,965, duplicates 8,920, rejected 863.
Deliveries 2 and 3 of the same file: accepted 0, duplicates 224,885, rejected 863 each.
Stored data did not change (R1). 3,000 assets, 253 open alerts after all three runs.

**The deadlock.** The full replay showed 46 retries and 1 failed batch. The feed repeats report
ids across batches, and two multi-row inserts that take the same unique keys in opposite order
wait for each other. The first lock-ordering plan covered only asset rows and missed this.
`DeadlockFreeIngestTest` reproduces it; rows are now inserted in report id order.

## Viewport query (1,000 assets in the answer)

| Load | p50 | p95 | p99 |
|---|---|---|---|
| One console | 8.4 ms | 13.5 ms | 24.7 ms |
| 20 consoles, back to back, no pause | | 869 ms | 2.4 s |
| 20 consoles, one request per second each (what real consoles do) | 59 ms | 117 ms | 129 ms |

The 100 ms target holds for one console and is exceeded slightly with 20 consoles each
asking for 1,000 assets every second on this emulated machine. The back-to-back figure is a
stress test, not a use case. Cause: CPU under emulation plus a 1,000-item JSON answer per
request. The query itself is about 2.7 ms in the database (sequential scan of 3,000 rows).
Not fixed in v1: a spatial index would not help at 3,000 rows. At larger scale the next step
is a GiST index on the position and a smaller default page.

## Query plans (EXPLAIN ANALYZE)

- Viewport: sequential scan on `asset_current`, 3,000 rows, ~2.7 ms.
- Track of one asset: bitmap heap scan plus sort, 72 rows, 1.9 ms.
- Alert page: index scan on the primary key.

## What the data looks like (first delivery)

- Rejected lines by reason: MALFORMED_JSON 281, INVALID_COORDINATE 222, MISSING_FIELD 220,
  INVALID_TIMESTAMP 110, INVALID_FIELD 30. Total 863 (0.4%).
- Stored reports: 215,965. SURFACE 169,337, AIR 46,628.
- Source prefixes: SAT 109,402, GND 73,205, MOB 33,141, no source 145, unknown prefix XYZ 72.
- Negative speed: 2 reports (stored as NULL). Heading outside 0-360: 2 (stored as NULL).
- Observed time range: 1970-01-01 to 2031-01-01 (some timestamps are far from the rest).
- Late reports (older than a newer report already received for that asset): 6,113.
- Assets currently inside at least one zone: 189; inside more than one: 10.
- Alerts by zone: Z-01 32, Z-02 32, Z-03 30, Z-04 45, Z-05 45, Z-06 30, Z-07 30, Z-08 4,
  Z-11 3, Z-12 2. No alerts for the advisory zones Z-09 and Z-10.

## Not measured yet

- Browser long-task profile of the console under a 5x replay.
- Automated accessibility scan (axe is not installed). Keyboard behaviour and roles are
  covered by the component tests.

## Rolling upgrade 1.0.0 to 1.1.0 (`deploy/scripts/test-rolling-upgrade.sh`)

The whole feed (215,965 reports) loaded into 1.0.0, then the upgrade steps of INSTALL.md run one by one
while two feeders (50-line batches) and four readers (viewport, track, alert list) ran continuously.

- Requests during the whole upgrade: **3,803 sent, 0 failed.**
- 1.0.0 delivering on the expanded schema: 3 of 3 batches answered HTTP 200; its rows had no type
  until the backfill.
- Backfill with traffic running: 4 s for 223,931 rows, including the catch-up run; `missing` 0 at the end.
- After step 7: column NOT NULL, index valid, types SATELLITE 109,402 / GROUND 73,208 / MOBILE 33,141 /
  UNKNOWN 217, 0 rows that disagree with the rule.

Limits of this test: about 20 requests per second for about two minutes, not the real load; live alert
streams were not part of the count (a stream on a replaced backend ends and the console reconnects, which
the console tests cover); no negative control (a naive restart of both backends at once) was run.

## Final offline install of 1.1.0 (`deploy/scripts/test-offline-install.sh 1.1.0`)

All images of the system removed first, then only the bundle files, a users file different from the development
one, and the five commands of INSTALL.md. Result: `sha256sum -c` OK for all five files; every service healthy;
health `UP`; console HTTP 200; login with the new file 200, with an old user 401, with a wrong password 401; the
backend has no route to another host (`wget: bad address`). The whole feed then loaded with 0 errors and 0
retries (18,455 lines/s); every row already had its type, `sql/20-verify.sql` showed `missing = 0`, and the
NOT NULL and index steps ran on the fresh system without a backfill.

Bundle files (each far below 2 GB): images 1.0.0 and 1.1.0 about 245 MB each, two deploy archives of a few KB,
`INSTALL.md`, `SHA256SUMS`.
