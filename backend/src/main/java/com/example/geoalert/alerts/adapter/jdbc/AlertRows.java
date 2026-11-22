package com.example.geoalert.alerts.adapter.jdbc;

import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** The alert columns and how a row becomes an {@link Alert}, shared by the JDBC repositories. */
final class AlertRows {

    static final String COLUMNS =
            "id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at,"
                    + " claimed_by, claimed_at, resolved_by, resolved_at, resolution_note";

    private AlertRows() {}

    static Alert map(ResultSet rs, int row) throws SQLException {
        return new Alert(
                rs.getLong("id"),
                rs.getString("asset_id"),
                rs.getString("zone_id"),
                AlertStatus.valueOf(rs.getString("status")),
                instant(rs, "opened_at"),
                rs.getString("trigger_report_id"),
                instant(rs, "trigger_observed_at"),
                rs.getString("claimed_by"),
                instant(rs, "claimed_at"),
                rs.getString("resolved_by"),
                instant(rs, "resolved_at"),
                rs.getString("resolution_note"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
