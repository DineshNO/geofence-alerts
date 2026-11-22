package io.geoalert.alerts.adapter.jdbc;

import io.geoalert.alerts.domain.AlertStatus;
import io.geoalert.alerts.domain.AuditAction;
import io.geoalert.alerts.domain.AuditEntry;
import io.geoalert.alerts.domain.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Inserts and reads only. There is no statement here that updates or deletes an entry. */
@Repository
public class JdbcAuditLog implements AuditLog {

    private final JdbcTemplate jdbc;

    public JdbcAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(
            long alertId,
            String actor,
            AuditAction action,
            AlertStatus fromStatus,
            AlertStatus toStatus) {
        jdbc.update(
                "INSERT INTO alert_audit (alert_id, actor, action, from_status, to_status)"
                        + " VALUES (?, ?, ?, ?, ?)",
                alertId,
                actor,
                action.name(),
                fromStatus == null ? null : fromStatus.name(),
                toStatus.name());
    }

    @Override
    public void recordOpened(List<Long> alertIds) {
        if (alertIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO alert_audit (alert_id, actor, action, from_status, to_status)"
                        + " VALUES (?, 'system', 'OPENED', NULL, 'OPEN')",
                alertIds,
                alertIds.size(),
                (ps, id) -> ps.setLong(1, id));
    }

    @Override
    public List<AuditEntry> forAlert(long alertId) {
        return jdbc.query(
                "SELECT at, actor, action, from_status, to_status FROM alert_audit"
                        + " WHERE alert_id = ? ORDER BY id",
                (rs, row) -> {
                    String from = rs.getString("from_status");
                    return new AuditEntry(
                            rs.getObject("at", OffsetDateTime.class).toInstant(),
                            rs.getString("actor"),
                            AuditAction.valueOf(rs.getString("action")),
                            from == null ? null : AlertStatus.valueOf(from),
                            AlertStatus.valueOf(rs.getString("to_status")));
                },
                alertId);
    }
}
