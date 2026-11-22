package com.example.geoalert.alerts.domain;

import java.util.List;

/**
 * Port: the append-only audit trail of alert state changes (R5).
 *
 * <p>It has no way to change or remove an entry: only {@code record} (an insert) and reading.
 * Every call to {@code record} happens in the same transaction as the change it describes, so
 * both are stored or neither is.
 */
public interface AuditLog {

    String SYSTEM = "system";

    /** Adds one entry. */
    void record(
            long alertId,
            String actor,
            AuditAction action,
            AlertStatus fromStatus,
            AlertStatus toStatus);

    /** Adds an OPENED entry by "system" for each alert that was just created. */
    void recordOpened(List<Long> alertIds);

    /** The entries of one alert, oldest first. */
    List<AuditEntry> forAlert(long alertId);
}
