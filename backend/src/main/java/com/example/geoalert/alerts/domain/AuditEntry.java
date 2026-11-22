package com.example.geoalert.alerts.domain;

import java.time.Instant;

/** One recorded change of an alert's state. {@code fromStatus} is null for OPENED. */
public record AuditEntry(
        Instant at, String actor, AuditAction action, AlertStatus fromStatus, AlertStatus toStatus) {}
