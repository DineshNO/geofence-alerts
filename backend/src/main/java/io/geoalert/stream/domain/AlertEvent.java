package io.geoalert.stream.domain;

/**
 * A change of an alert, small on purpose: the console refetches the alert for its full state, so
 * a late or reordered event can never put an older state on screen. The asset and zone ids are
 * there so a screen reader can be told what happened without waiting for the refetch.
 */
public record AlertEvent(String type, String alertId, String status, String assetId, String zoneId) {}
