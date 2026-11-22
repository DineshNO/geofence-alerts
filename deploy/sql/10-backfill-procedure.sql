-- CR-1 backfill: gives every old report its source_type. Run once, any time after the new
-- version is installed. Safe to run while the system is in use, safe to stop and run again.
--
--   docker compose exec -T postgres psql -U geoalert -d geoalert -f /sql/10-backfill-procedure.sql
--   docker compose exec -T postgres psql -U geoalert -d geoalert -c "CALL backfill_source_type(10000, 50)"
--
-- How it behaves:
--  * It walks the table in id order, 10,000 rows (parameter 1) per batch, and COMMITS after each
--    batch. A batch holds row locks for a few milliseconds and the transaction is short, so no
--    query or ingest waits on it, and a stop loses at most one batch.
--  * It pauses 50 ms (parameter 2) between batches so ingest and the console keep their share of
--    the disk and CPU. Raise it when the system is busy, set 0 to go flat out.
--  * Only rows with source_type IS NULL are touched, so rows written by version 1.1.0 are never
--    rewritten, and running it again is harmless.
--  * The position it has reached is in backfill_progress, so a restart continues there, and
--    anyone can see how far it is: SELECT * FROM backfill_progress;
--  * The rule is the same as SourceType in the application (a test keeps them identical).

CREATE TABLE IF NOT EXISTS backfill_progress (
    name        text PRIMARY KEY,
    last_id     bigint      NOT NULL,
    rows_done   bigint      NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE PROCEDURE backfill_source_type(batch_size integer DEFAULT 10000, pause_ms integer DEFAULT 50)
LANGUAGE plpgsql
AS $$
DECLARE
    cursor_id bigint;
    next_id   bigint;
    touched   integer;
BEGIN
    INSERT INTO backfill_progress (name, last_id, rows_done)
    VALUES ('source_type', 0, 0)
    ON CONFLICT (name) DO NOTHING;
    COMMIT;

    LOOP
        SELECT last_id INTO cursor_id FROM backfill_progress WHERE name = 'source_type';

        -- the upper end of this batch: the id that is batch_size rows further on (or the end)
        SELECT max(id) INTO next_id
        FROM (SELECT id FROM reports WHERE id > cursor_id ORDER BY id LIMIT batch_size) b;
        EXIT WHEN next_id IS NULL;

        UPDATE reports
           SET source_type = CASE WHEN source LIKE 'SAT%' THEN 'SATELLITE'
                                  WHEN source LIKE 'GND%' THEN 'GROUND'
                                  WHEN source LIKE 'MOB%' THEN 'MOBILE'
                                  ELSE 'UNKNOWN' END
         WHERE id > cursor_id AND id <= next_id AND source_type IS NULL;
        GET DIAGNOSTICS touched = ROW_COUNT;

        UPDATE backfill_progress
           SET last_id = next_id, rows_done = rows_done + touched, updated_at = now()
         WHERE name = 'source_type';
        COMMIT;

        IF pause_ms > 0 THEN
            PERFORM pg_sleep(pause_ms / 1000.0);
        END IF;
    END LOOP;
END
$$;
