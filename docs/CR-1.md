# CR-1: `source_type`, upgrade 1.0.0 to 1.1.0 without stopping the service

**What changes.** Every report gets a `source_type` (SATELLITE, GROUND, MOBILE, UNKNOWN), derived from
the prefix of `source` (SAT, GND, MOB, anything else or nothing is UNKNOWN). The track answers carry
`sourceType` and can be filtered with `?sourceType=`. The rule exists once in Java (`SourceType`) and
once in SQL (used for old rows and by the backfill); a test runs both on the same inputs.

**The idea.** The old and the new version both run against the same database for a while, so every
step must be safe for both. Old rows are **never** given a made-up value: until the backfill reaches a
row, the new version derives its type from `source` on read, so answers are identical before, during and
after the backfill. Commands are in `INSTALL.md` (section "Upgrade"); `deploy/scripts/test-rolling-upgrade.sh`
runs them all against real traffic.

| # | Step | Lock and cost | Verify | Roll back |
|---|---|---|---|---|
| 1 | **Expand**: `docker compose run --rm migrate` adds `source_type text NULL` (no default) | catalog change only: a brief ACCESS EXCLUSIVE lock, no rewrite, any table size | `\d reports` shows the column; 1.0.0 still delivers and reads (it names its columns in every statement) | `ALTER TABLE reports DROP COLUMN source_type` (nothing uses it yet) |
| 2 | **Backend-1**: remove from the load balancer, reload, replace, wait healthy, add back | none on the database; nginx lets in-flight requests finish, streams on it reconnect to the other backend | `docker compose ps` healthy; a track call returns `sourceType` | `BACKEND_1_IMAGE=geoalert/backend:1.0.0 docker compose up -d --no-deps backend-1`, same removal first |
| 3 | **Backend-2**: the same, taking backend-2 out | same | same | same |
| 4 | **Console** 1.1.0 | a few seconds without the page; the API is unaffected | the table of recent track shows "Source type" | `CONSOLE_IMAGE=geoalert/console:1.0.0 docker compose up -d --no-deps console` |
| 5 | **Backfill**: `CALL backfill_source_type(10000, 50)` | batches of 10,000 rows, one commit each, 50 ms pause; row locks for milliseconds, so ingest and reads never wait; the cost is I/O and WAL | `sql/20-verify.sql`: `missing` falls to 0; progress is in `backfill_progress` | stop it any time (Ctrl-C); nothing is lost beyond one batch; a re-run continues. Values can be reset with `UPDATE reports SET source_type = NULL` and the backfill run again |
| 6 | **Catch-up**: the same call again | touches only rows still NULL (rows written by 1.0.0 during the rollout) | `missing` = 0 | none needed |
| 7 | **NOT NULL**: `sql/30-not-null.sql` | `ADD CONSTRAINT ... NOT VALID` (short lock), `VALIDATE` (full scan but reads and writes continue), `SET NOT NULL` (instant, uses the validated constraint) | column `is_nullable = NO` | `ALTER TABLE reports ALTER COLUMN source_type DROP NOT NULL` |
| 8 | **Index**: `sql/40-index.sql`, `CREATE INDEX CONCURRENTLY` on (asset_id, source_type, observed_at, id) | builds beside live writes, no write lock; extra I/O | index `indisvalid` is true | `DROP INDEX CONCURRENTLY reports_asset_type_observed_idx` |

**Precondition for step 7.** No 1.0.0 instance may still run, because it inserts rows without the column
and step 7 makes that illegal. In practice: steps 2 and 3 are finished, and `missing` is 0. If a NULL is
left, `VALIDATE` fails and nothing has changed. After step 7, going back to 1.0.0 needs the rollback of
step 7 first.

**Why a SQL procedure and not application code or Liquibase.** The backfill is data movement, not
schema. A Liquibase changeset would run inside the migrate step in one transaction and hold row locks for
the whole table. A procedure that commits per batch, can be throttled, stopped and resumed, and runs
inside the database (no round trips), is what a 500-million-row table needs. Liquibase still owns the
schema change of step 1.

## How long at 500 million rows

Measured: 216,000 rows are backfilled in about 4 seconds in this test (a laptop, emulated x86, a small table
that fits in memory), about 55,000 rows/s with the 50 ms pause. That would be about 2.5 hours for 500 million.
That number is **not** a forecast: at that size the table does not fit in memory, so plan for 3 to 8 times
longer, **6 to 20 hours**, and treat the measured rate as a best case.

What dominates at that size, and the throttle for each (estimates, not measurements):
* **WAL and disk.** Every updated row is written again: roughly the table size in new row versions
  and a similar amount of WAL. Have free disk of about the size of the table, and a WAL archive or
  `max_wal_size` that can absorb it (the compose file sets 2 GB, so checkpoints will be frequent;
  raise it for the run).
* **Bloat.** Old row versions become dead tuples; autovacuum reclaims them as it goes. Watch
  `pg_stat_user_tables.n_dead_tup`; run `VACUUM reports` between runs if it lags.
* **Throttling.** Raise the pause (`CALL backfill_source_type(5000, 500)`) while the console is busy
  and lower it at night. The batch size bounds how long any lock is held.
* **Replication lag** (if a replica is added later): the procedure commits in small steps, so lag
  stays proportional to the pause; increase the pause when lag grows.

## What was tested

`Cr1SqlStepsTest` (backfill values equal the Java rule, a restart changes nothing, a late row is caught,
NOT NULL is refused while a NULL exists and accepted after, the index is valid), `SourceTypeTest` (Java and
SQL agree on 21 inputs), `TrackSourceTypeApiTest` (old rows without a type are filtered and shown
correctly), `SourceTypeWriteTest` (1.1.0 stores the type), and `test-rolling-upgrade.sh`: the whole
feed loaded into 1.0.0, 1.0.0 delivering on the expanded schema (HTTP 200), then the steps above with
six clients running, ending with every row typed, the column required and the index valid.
The result of the last run is in `docs/MEASUREMENTS.md`.
