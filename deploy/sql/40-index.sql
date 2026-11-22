-- CR-1 contract step 2: the index that serves "this asset, this source type, in time order".
-- CONCURRENTLY builds it without blocking writes. It cannot run inside a transaction, so this
-- file is a single statement run by psql on its own. If it fails it leaves an INVALID index:
-- DROP INDEX CONCURRENTLY reports_asset_type_observed_idx; and run it again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS reports_asset_type_observed_idx
    ON reports (asset_id, source_type, observed_at, id);
