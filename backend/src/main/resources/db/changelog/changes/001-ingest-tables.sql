--liquibase formatted sql

--changeset geoalert:001-reports
-- The track: one row per accepted report, never updated or deleted by the application.
-- report_id is unique: the unique index is what makes a repeated delivery change nothing (R1).
CREATE TABLE reports (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id    varchar(64)      NOT NULL,
    asset_id     varchar(32)      NOT NULL,
    observed_at  timestamptz      NOT NULL,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    kind         text,
    speed_kn     double precision,
    heading_deg  double precision,
    source       text
);
CREATE UNIQUE INDEX reports_report_id_key ON reports (report_id);
-- serves the track endpoint: one asset, oldest first, no sort
CREATE INDEX reports_asset_observed_idx ON reports (asset_id, observed_at, id);
--rollback DROP TABLE reports;

--changeset geoalert:002-asset-current
-- The current position: one row per asset, a small read model beside the track (R2).
-- zone_ids lists every zone that contains the position (restricted and advisory).
-- The table is updated constantly, so a lower fill factor leaves room for in-place updates.
CREATE TABLE asset_current (
    asset_id     varchar(32)      PRIMARY KEY,
    report_id    varchar(64)      NOT NULL,
    observed_at  timestamptz      NOT NULL,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    kind         text,
    speed_kn     double precision,
    heading_deg  double precision,
    source       text,
    zone_ids     text[]           NOT NULL DEFAULT '{}'
) WITH (fillfactor = 70);
--rollback DROP TABLE asset_current;

--changeset geoalert:003-ingest-batches
-- Append-only log of delivered batches. Stats are the sums of this table. Rows are only
-- inserted, so parallel batches never wait on each other.
CREATE SEQUENCE ingest_batch_seq;
CREATE TABLE ingest_batches (
    id           bigint      PRIMARY KEY DEFAULT nextval('ingest_batch_seq'),
    request_id   varchar(64),
    instance     varchar(64),
    received_at  timestamptz NOT NULL DEFAULT now(),
    received     integer     NOT NULL,
    accepted     integer     NOT NULL,
    duplicates   integer     NOT NULL,
    rejected     integer     NOT NULL
);
CREATE INDEX ingest_batches_received_at_idx ON ingest_batches (received_at);
--rollback DROP TABLE ingest_batches; DROP SEQUENCE ingest_batch_seq;

--changeset geoalert:004-rejected-lines
-- Every rejected line: what it was (truncated by the application), why, and when (R6).
CREATE TABLE rejected_lines (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id    bigint      NOT NULL,
    line_no     integer     NOT NULL,
    code        varchar(32) NOT NULL,
    detail      text,
    raw_line    text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
--rollback DROP TABLE rejected_lines;
