package com.example.geoalert.assets.domain;

import java.time.Instant;

/** One stored report of an asset. */
public record TrackPoint(String reportId, Instant observedAt, double lat, double lon, String source) {}
