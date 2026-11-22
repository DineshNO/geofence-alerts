package io.geoalert.alerts.domain;

import java.time.Instant;

/** An alert as stored. The contract's alertId is {@code id} as a string. */
public record Alert(
        long id,
        String assetId,
        String zoneId,
        AlertStatus status,
        Instant openedAt,
        String triggerReportId,
        Instant triggerObservedAt,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {}
