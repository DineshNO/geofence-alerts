package com.example.geoalert.ingest.domain;

import java.time.Instant;

/** A report that passed validation, with typed values. Optional values may be null. */
public record ParsedReport(
        String reportId,
        String assetId,
        Instant observedAt,
        double lat,
        double lon,
        String kind,
        Double speedKn,
        Double headingDeg,
        String source) {}
