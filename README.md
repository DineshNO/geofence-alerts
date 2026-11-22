# geofence-alerts

A geofence alert system. It takes a feed of positions of about 3,000 ships and aircraft, raises an alert when
an asset **enters a RESTRICTED zone**, and lets operators claim, release and resolve those alerts with a full
audit trail. A console shows the live map and the alert panel.

* **Backend:** Java 21, Spring Boot 3.5, PostgreSQL 16, plain SQL (no ORM), Liquibase, JTS for geometry.
* **Console:** React 18, TypeScript (strict), MapLibre GL on a blank map (no tiles, no outside requests), TanStack Query.
* **Delivery:** an offline Docker Compose bundle: two backend instances behind an nginx load balancer, one database.
  See [INSTALL.md](INSTALL.md) for the five commands, and [docs/RUNBOOK.md](docs/RUNBOOK.md) for operation.
* **Versions:** v1.0.0 is the full system. v1.1.0 is change request CR-1 (`source_type`, a rolling upgrade with no
  downtime): [docs/CR-1.md](docs/CR-1.md).

| Folder | Purpose |
|---|---|
| `contract/` | the fixed API contract (OpenAPI), extended only by added endpoints and fields |
| `data/`, `tools/` | zones, and the feed replay tool |
| `backend/`, `frontend/` | the service and the console |
| `deploy/` | compose file, load balancer settings, backfill SQL, bundle and test scripts |
| `docs/` | CR-1, runbook, measurements, alert lifecycle, live alert stream, zone map |

## Run and test

```
(cd backend && ./mvnw verify)              # 272 tests; needs Docker (they run against a real PostgreSQL)
(cd frontend && npm ci && npm test)        # 77 tests
deploy/scripts/build-images.sh 1.1.0 && deploy/scripts/build-bundle.sh 1.0.0 1.1.0
deploy/scripts/test-offline-install.sh 1.1.0 18080     # installs from the bundle on a clean image state
deploy/scripts/test-rolling-upgrade.sh path/to/feed.ndjson.gz 18081
```

## Develop locally

Needs Docker, JDK 21 and Node.

```
docker run -d --name geoalert-dev-db -p 55432:5432 -e POSTGRES_DB=geoalert -e POSTGRES_USER=geoalert -e POSTGRES_PASSWORD=geoalert postgres:16.16-alpine
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # the backend on :8080
cd frontend && npm ci && npm run dev                                    # the console on :5173
```

The `dev` profile is `backend/config/application-dev.yml` (database, a throwaway signing key, port) and
`backend/config/dev-users.json`; that folder is not packaged into the jar or the image. Sign in as `op01`, `op02`,
`sup01` or `feed` with `dev-password`. On another port, add `-Dspring-boot.run.arguments=--server.port=18080` and start
the console with `BACKEND_URL=http://localhost:18080 npm run dev`. Load data with
`python3 tools/replay.py --file path/to/feed.ndjson.gz --base-url http://localhost:8080 --username feed --password dev-password --speed max`.

## The six rules, how each is enforced, and the test

| Rule | Mechanism | Tests |
|---|---|---|
| **R1** A duplicate changes nothing | `INSERT ... ON CONFLICT (report_id) DO NOTHING RETURNING`; only returned rows continue to position and alert. Rows go in report-id order (see "what the replay found") | `IngestApiTest` (same batch again, repeated inside a batch, same batch in parallel, overlapping parallel batches), `DeadlockFreeIngestTest` |
| **R2** The newest event time is the current position | `asset_current`, one row per asset, changed with `UPDATE ... WHERE observed_at < new`; new assets by `INSERT ... ON CONFLICT DO NOTHING`; existing rows locked `FOR UPDATE` in asset-id order | `PositionOrderTest` (late, equal time, reversed, shuffled across parallel batches, one current row per asset) |
| **R3** One active alert per asset and zone | partial unique index on `alerts (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED')`, insert with `ON CONFLICT DO NOTHING` | `AlertCreationTest` (parallel batches all entering one zone, many writers creating one alert, a new alert after resolve) |
| **R4** One owner | `SELECT ... FOR UPDATE` on the alert, rules in the pure `AlertRules` state machine, update in the same transaction | `AlertCommandTest` (20 operators claim at once: exactly one wins; parallel claim/release/resolve stay consistent), `AlertRulesTest` |
| **R5** Audit in the same transaction | the application writes the audit entry beside the change; the `AuditLog` port can only add and read | `AuditTrailTest` (if the entry cannot be written, the change is not stored; repeats and refusals write nothing; there is no way to change an entry) |
| **R6** Bad lines are isolated, counted, inspectable | each line is validated before the database; a bad line is stored in `rejected_lines` with code and reason; counts always add up (`received = accepted + duplicates + rejected`); `GET /api/v1/rejections` | `LineValidatorTest` (80 cases), `IngestApiTest` (bad lines do not block good ones, kept truncated), `BatchFailurePolicyTest` |

## The five decisions that matter, and what was rejected

1. **The database enforces the rules, the application does not.** Unique indexes and row locks decide between two
   instances and parallel requests; nothing is kept in memory or in a cache. *Rejected:* check-then-insert in code
   (two requests both see "not found"), an in-memory lock or cache (wrong with two instances), Redis (one more thing
   to ship and run offline, and a second copy of the truth).
2. **A current-position table beside the track (R2).** The map reads 3,000 rows, not "the newest report per asset"
   out of hundreds of thousands. *Rejected:* deriving the position from the track on every query (too slow for the
   100 ms target), a per-instance cache.
3. **Audit written by Java in the same transaction (R5), not by a trigger.** The application knows who acted; a
   trigger sees only a database user and hides logic where reviewers do not look. *Rejected:* triggers, event
   sourcing with replay (the audit is a record, not the source of state).
4. **One failure policy for a batch.** A bad *line* is isolated (R6); a *database* failure fails the whole batch
   with a 500, because the feed's delivery is at-least-once and a resend is safe (R1). Only transient SQLSTATEs
   (deadlock, serialization, connection class, shutdown, too many connections) are retried, then the batch fails
   and raises an operational alert (ERROR log and a counter). *Rejected:* splitting a failed batch line by line
   (slows everyone waiting behind it), retrying everything (a schema error will never succeed).
5. **Live alerts by SSE and PostgreSQL LISTEN/NOTIFY.** A change committed on either instance reaches the streams
   of both; nothing is sent for a rolled-back change. The event is small (type, alert id, status, asset, zone) and
   the console refetches. *Rejected:* WebSocket (two-way, not needed), polling the alert list (delay and load), a
   message broker (another component offline). Detail in [docs/live-alert-stream.md](docs/live-alert-stream.md).

Also: JTS for point-in-polygon with `covers` (a point on an edge is inside; on the edge of a hole also inside), the
180 degree meridian handled by making Z-08's longitudes continuous; statistics as the sum of an append-only batch log
(parallel batches never wait on a counter row). A daily checkpoint is the scale answer and is **not built**.

## What the data showed

Full numbers are in [docs/MEASUREMENTS.md](docs/MEASUREMENTS.md). In short:

* **Zones:** Z-03 has a hole, Z-06 has two parts, Z-08 and Z-12 are at the 180 degree line, Z-02 and Z-08 are wound
  clockwise (harmless), Z-04 and Z-05 overlap (an asset in the overlap raises two alerts), Z-09 and Z-10 are advisory
  (drawn, never alert), Z-11 is 0.03 degrees wide (a fast asset can skip it between two reports; we do not interpolate).
* **Feed:** 225,748 lines: 215,965 accepted, 8,920 duplicates (4%), 863 rejected (0.4%: 281 malformed JSON, 222 bad
  coordinate, 220 missing field, 110 bad timestamp, 30 bad field). 6,113 late reports. 2 negative speeds and 2
  headings out of range (kept as null; an optional field never rejects a line). 145 reports with no source and 72 with
  an unknown prefix. Observed times range from 1970 to 2031.

## What the full replay found (and the tests did not)

All parallel tests passed, and still the first full replay with four connections showed 46 retries and one failed batch:
a deadlock in the `reports` insert. The feed repeats report ids across batches, so two multi-row inserts took the same
unique keys in opposite order and waited for each other. The lock ordering I had planned covered asset rows only. A test
(`DeadlockFreeIngestTest`) now reproduces it, and the fix is one sort by report id. After it: 0 retries and 0 failures
in three full replays, and ingest went from 4,333 to 19,750 lines/s.

## Evidence and its limits

| Evidence | Result | Where |
|---|---|---|
| Ingest, full feed, 4 connections, two backends | 19,750 lines/s, 0 errors; replaying twice more stores nothing | MEASUREMENTS |
| Viewport query, 1,000 assets | one console p95 13.5 ms; 20 consoles polling once a second p95 117 ms (**above the 100 ms target**); 20 consoles back to back p95 869 ms | MEASUREMENTS |
| Query plans | viewport is a sequential scan of 3,000 rows (2.7 ms), track uses the (asset, time) index, alert page uses the primary key | MEASUREMENTS |
| Rolling upgrade under traffic | 3,803 requests, 0 failed | MEASUREMENTS, CR-1 |
| Offline install | all images removed first; five commands start it; a different users file works; the backend has no route to any other host | `test-offline-install.sh` |
| Console | 77 component and unit tests; map with 3,000 assets, alert panel, zones table checked in a browser | `frontend/src` |

**Honest gaps:** the measurements ran on a Mac under Docker with x86 emulation, not the 2-core Linux target, and not
for long. The 100 ms viewport target is met for one console and missed for 20 consoles on this machine (CPU under
emulation plus 1,000 items per answer). **No browser long-task profile at 5x replay was captured.** **No automated
accessibility scan was run** (axe is not installed here); keyboard operation, roles and labels are covered by the
component tests and by manual checks only. These are the first things to do next.

## Written answers

### Building the images with no internet

The target machine builds nothing; images are built on a connected machine and shipped (`docker save`, checksums).
If the images must also be **rebuilt** inside the air gap, three things must be inside it: (1) a copy of the base images
by exact tag and digest (`postgres`, `nginx-unprivileged`, `eclipse-temurin`, a node image for the console) in a local
registry or as tar files; (2) the build dependencies: a Maven repository mirror (an artifact server, or a repository
folder filled once by `mvn dependency:go-offline` and carried over) and an npm cache or mirror (`npm ci` needs only the
lockfile and the packages); (3) pinned versions everywhere (this repository pins the Maven plugins through the Spring Boot
parent, JTS and the base images by version, and the npm packages by lockfile) so a rebuild gives the same result. Our
Dockerfiles only copy a jar and a built console, so the air-gapped step is small; the build of the jar and the console
is the part that needs the mirrors. Each transfer is checked with `sha256sum`, and a list of every component with its
version (an SBOM) should travel with the bundle so the security team can review it.

### Moving to MariaDB

What carries over: the model (a track table, a current-position table, an alert table with its audit table), Liquibase,
plain SQL with row locks (`SELECT ... FOR UPDATE` behaves the same in InnoDB), and sorting inserts by key to avoid
deadlocks. What changes: (1) **R1:** `ON CONFLICT DO NOTHING RETURNING` becomes `INSERT IGNORE` (or `ON DUPLICATE KEY`)
followed by finding which rows were really inserted, since MariaDB's `RETURNING` does not tell inserted from ignored;
(2) **R3:** there are no partial indexes, so a generated column that is NULL once the alert is resolved, with a unique
index on (asset, zone, that column), gives the same guarantee (a unique index allows many NULLs); (3) `zone_ids text[]`
becomes a JSON column or a small `asset_zone` table; (4) **LISTEN/NOTIFY does not exist**: live alerts would need a
small outbox table that each instance polls (a second or less), which costs latency; (5) types: `timestamptz` becomes
`DATETIME(3)` stored in UTC; (6) CR-1 steps: `ADD COLUMN` is instant on recent MariaDB; `NOT VALID` constraints do not
exist, `MODIFY ... NOT NULL` is an online but rebuilding change; `CREATE INDEX CONCURRENTLY` becomes online DDL
(`ALGORITHM=INPLACE, LOCK=NONE`); the backfill procedure with a COMMIT per batch is still the right tool. Everything
that is plain SQL and tested against the real database would be re-run on MariaDB first; the test suite is the plan.

## Known limits

* Statistics are a sum over the batch log. Fine at this size; at hundreds of millions of batches a daily checkpoint row
  is the answer (designed, not built).
* The viewport query scans all 3,000 rows. At a much larger fleet it needs a spatial index and a smaller default page.
* Positions are polled about once a second; only alerts are pushed.
* No interpolation: a fast asset can cross a small zone between two reports without an alert.
* Users come from a file read at start; there is no user management. The console keeps the session in the tab's sessionStorage for the 30-minute token life (a reload keeps you signed in, closing the tab forgets it); there is no token refresh, so after 30 minutes you sign in again, and a script on the page could read the token.
* One database, no replica, no backup procedure beyond the volume.
* One load balancer and one console container: replacing them takes a few seconds of interruption.

## AI-assisted development disclosure

An AI assistant (Claude) was used throughout: to explain the domain, to discuss and record design options, to write
code, tests and documents, and to run the measurements. The decisions were made by the author in conversation, and every
decision above has a stated alternative. Everything was run: the tests, the replays, the offline install and the upgrade.

Cases where the AI's output was **wrong** and how each was found:

1. **A company name was written into `.gitignore`** (and the first repository's history carried the brief's wording).
   Found on review before anything was pushed; the work was redone in a new repository with neutral wording.
2. **The cost of splitting a failed batch was overstated** (halving retries were described as much more expensive than
   they are). The author questioned it; the final policy is a different one (fail the whole batch, rely on safe resend).
3. **A decision-log entry about the JDBC approach was written the opposite way round from what was decided.** The
   author spotted it while reading the log, and the record was corrected.
4. **The lock-ordering plan covered only asset rows and missed the unique keys of `reports`.** All parallel tests
   passed. Only the full replay produced deadlocks; fixed by sorting by report id, with a test that reproduces it.
5. **The JWT clock tolerance was left at Spring's default of 60 seconds**, so an expired token was still accepted for a
   minute. A test with a short-lived token failed; the decoder now allows one second.
6. **A statistics controller read the database directly**, against the layering rule. Found in review and split into
   controller, service and repository.
7. **The first version of the upgrade steps broke the load balancer**: the extracted archive replaced a bind-mounted
   file, so `nginx -s reload` failed. Found only by running the upgrade for real; the settings are now mounted as folders.

What was *not* delegated: the choice of rules mechanisms, the failure policy, the audit decision, the statistics
checkpoint idea, and what to write in this README about the gaps.
