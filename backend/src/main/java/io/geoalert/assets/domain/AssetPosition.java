package io.geoalert.assets.domain;

import java.time.Instant;

/** The current position of one asset, as the contract describes it. */
public record AssetPosition(
        String assetId,
        String kind,
        String reportId,
        Instant observedAt,
        double lat,
        double lon,
        Double speedKn,
        Double headingDeg,
        String source) {}
