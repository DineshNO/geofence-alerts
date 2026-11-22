-- How many reports still have no source_type, and how far the backfill is.
-- After the last old instance is gone and the backfill has finished, "missing" must be 0.
SELECT (SELECT count(*) FROM reports WHERE source_type IS NULL) AS missing,
       (SELECT last_id FROM backfill_progress WHERE name = 'source_type') AS backfill_at_id,
       (SELECT max(id) FROM reports) AS newest_id;
