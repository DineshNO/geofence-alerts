--liquibase formatted sql

--changeset geoalert:005-alerts
-- One row per alert. The contract's alertId is this id as a string.
CREATE TABLE alerts (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id             varchar(32) NOT NULL,
    zone_id              varchar(64) NOT NULL,
    status               varchar(16) NOT NULL CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED')),
    opened_at            timestamptz NOT NULL DEFAULT now(),
    trigger_report_id    varchar(64) NOT NULL,
    trigger_observed_at  timestamptz NOT NULL,
    claimed_by           varchar(64),
    claimed_at           timestamptz,
    resolved_by          varchar(64),
    resolved_at          timestamptz,
    resolution_note      text
);
-- R3: for one asset and one zone there is never more than one alert that is OPEN or CLAIMED.
-- The database enforces it, so no code path can break it, on either instance.
CREATE UNIQUE INDEX alerts_one_active_per_asset_zone
    ON alerts (asset_id, zone_id)
    WHERE status IN ('OPEN', 'CLAIMED');
-- each list filter pages by key
CREATE INDEX alerts_status_id_idx ON alerts (status, id);
CREATE INDEX alerts_asset_id_idx ON alerts (asset_id, id);
CREATE INDEX alerts_zone_id_idx ON alerts (zone_id, id);
--rollback DROP TABLE alerts;

--changeset geoalert:006-alert-audit
-- Append-only record of every change of an alert's state (R5). The application only
-- inserts and reads this table.
CREATE TABLE alert_audit (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     bigint      NOT NULL REFERENCES alerts (id),
    at           timestamptz NOT NULL DEFAULT now(),
    actor        varchar(64) NOT NULL,
    action       varchar(16) NOT NULL CHECK (action IN ('OPENED', 'CLAIMED', 'RELEASED', 'RESOLVED')),
    from_status  varchar(16),
    to_status    varchar(16) NOT NULL
);
CREATE INDEX alert_audit_alert_id_idx ON alert_audit (alert_id, id);
--rollback DROP TABLE alert_audit;

--changeset geoalert:007-users
-- Users are seeded at start-up from a mounted file; only the password hash is stored.
CREATE TABLE users (
    username       varchar(64) PRIMARY KEY,
    role           varchar(16) NOT NULL CHECK (role IN ('INGEST', 'OPERATOR', 'SUPERVISOR')),
    password_hash  varchar(100) NOT NULL
);
--rollback DROP TABLE users;
