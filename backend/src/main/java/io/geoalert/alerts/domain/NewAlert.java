package io.geoalert.alerts.domain;

import java.time.Instant;

/** An alert that is due: an asset entered a restricted zone with this report. */
public record NewAlert(
        String assetId, String zoneId, String triggerReportId, Instant triggerObservedAt) {}
