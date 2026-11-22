package com.example.geoalert.zones.domain;

/**
 * A zone for display. {@code geometry} is GeoJSON (a Polygon or MultiPolygon as a plain map).
 * A zone that crosses the 180 degree meridian has its longitudes made continuous (for example
 * 180.6 instead of -179.4), so a map draws it as one shape, not a band across the world.
 */
public record Zone(
        String id,
        String name,
        ZoneClass zoneClass,
        boolean crossesAntimeridian,
        Object geometry) {}
