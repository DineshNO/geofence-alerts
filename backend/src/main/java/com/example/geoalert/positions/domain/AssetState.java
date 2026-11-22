package com.example.geoalert.positions.domain;

import com.example.geoalert.ingest.domain.ParsedReport;
import java.time.Instant;
import java.util.Set;

/** The current position of one asset, and the zones that contain it. */
public record AssetState(
        String assetId,
        String reportId,
        Instant observedAt,
        double lat,
        double lon,
        String kind,
        Double speedKn,
        Double headingDeg,
        String source,
        Set<String> zoneIds) {

    public static AssetState from(ParsedReport r, Set<String> zoneIds) {
        return new AssetState(
                r.assetId(),
                r.reportId(),
                r.observedAt(),
                r.lat(),
                r.lon(),
                r.kind(),
                r.speedKn(),
                r.headingDeg(),
                r.source(),
                Set.copyOf(zoneIds));
    }
}
