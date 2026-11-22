-- CR-1 contract step 1: make source_type NOT NULL without a long lock.
--
-- PRECONDITION: no instance of version 1.0.0 is running (it inserts rows without the column,
-- which this step makes illegal) and 20-verify.sql shows missing = 0. If a row is still NULL,
-- VALIDATE fails and nothing has changed; run the backfill again and retry.
--
-- 1. NOT VALID: checks only new rows, takes a short lock, scans nothing.
-- 2. VALIDATE: scans the table, but only holds a lock that lets reads and writes continue.
-- 3. SET NOT NULL: PostgreSQL sees the validated constraint and skips its own full scan.
-- 4. The helper constraint is dropped.
ALTER TABLE reports ADD CONSTRAINT reports_source_type_nn CHECK (source_type IS NOT NULL) NOT VALID;
ALTER TABLE reports VALIDATE CONSTRAINT reports_source_type_nn;
ALTER TABLE reports ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE reports DROP CONSTRAINT reports_source_type_nn;
