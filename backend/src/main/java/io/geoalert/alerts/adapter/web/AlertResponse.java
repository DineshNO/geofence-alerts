package io.geoalert.alerts.adapter.web;

import io.geoalert.alerts.domain.Alert;
import java.time.Instant;

/** An alert as the contract describes it. */
public record AlertResponse(
        String alertId,
        String assetId,
        String zoneId,
        String status,
        Instant openedAt,
        String triggerReportId,
        Instant triggerObservedAt,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {

    public static AlertResponse of(Alert a) {
        return new AlertResponse(
                Long.toString(a.id()),
                a.assetId(),
                a.zoneId(),
                a.status().name(),
                a.openedAt(),
                a.triggerReportId(),
                a.triggerObservedAt(),
                a.claimedBy(),
                a.claimedAt(),
                a.resolvedBy(),
                a.resolvedAt(),
                a.resolutionNote());
    }
}
