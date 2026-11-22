package com.example.geoalert.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.geoalert.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** R3 in the database: one active alert per asset and zone, enforced by a partial unique index. */
class AlertSchemaTest extends PostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private long insertAlert(String asset, String zone, String status) {
        return jdbc.queryForObject(
                "INSERT INTO alerts (asset_id, zone_id, status, trigger_report_id,"
                        + " trigger_observed_at) VALUES (?, ?, ?, 'r-1', now()) RETURNING id",
                Long.class,
                asset,
                zone,
                status);
    }

    @Test
    void aSecondOpenAlertForTheSameAssetAndZoneIsRefused() {
        insertAlert("AST-R3A", "Z-01", "OPEN");
        assertThatThrownBy(() -> insertAlert("AST-R3A", "Z-01", "OPEN"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aClaimedAlertStillBlocksANewOne() {
        insertAlert("AST-R3B", "Z-01", "CLAIMED");
        assertThatThrownBy(() -> insertAlert("AST-R3B", "Z-01", "OPEN"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void afterResolvingTheNextEntryMayRaiseANewAlert() {
        insertAlert("AST-R3C", "Z-01", "RESOLVED");
        long second = insertAlert("AST-R3C", "Z-01", "OPEN");
        assertThat(second).isPositive();
        // and a resolved alert history can grow
        insertAlert("AST-R3C", "Z-01", "RESOLVED");
    }

    @Test
    void differentZonesAndAssetsDoNotBlockEachOther() {
        insertAlert("AST-R3D", "Z-01", "OPEN");
        insertAlert("AST-R3D", "Z-02", "OPEN");
        insertAlert("AST-R3E", "Z-01", "OPEN");
    }

    @Test
    void anUnknownStatusIsRefused() {
        assertThatThrownBy(() -> insertAlert("AST-R3F", "Z-01", "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theAuditTableOnlyAcceptsKnownActions() {
        long alertId = insertAlert("AST-R5A", "Z-01", "OPEN");
        jdbc.update(
                "INSERT INTO alert_audit (alert_id, actor, action, to_status)"
                        + " VALUES (?, 'system', 'OPENED', 'OPEN')",
                alertId);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO alert_audit (alert_id, actor, action,"
                                                + " to_status) VALUES (?, 'x', 'DELETED', 'OPEN')",
                                        alertId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
