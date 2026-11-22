package io.geoalert.assets.domain;

import java.time.Instant;

/**
 * One stored report of an asset. {@code sourceType} is the stored type, or for a row the
 * backfill has not reached yet, the type derived from {@code source} (CR-1).
 */
public record TrackPoint(
        String reportId,
        Instant observedAt,
        double lat,
        double lon,
        String source,
        SourceType sourceType) {}
