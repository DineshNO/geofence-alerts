--liquibase formatted sql

--changeset geoalert:003-reports-source-type
-- CR-1, step "expand". A nullable column with no default is a catalog-only change in
-- PostgreSQL: it takes a brief ACCESS EXCLUSIVE lock and rewrites nothing, whatever the size
-- of the table. Version 1.0.0 does not know the column and keeps working (it names its
-- columns in every statement). Old rows stay NULL until the backfill (deploy/sql).
ALTER TABLE reports ADD COLUMN source_type text;
--rollback ALTER TABLE reports DROP COLUMN source_type;
