package com.example.geoalert.assets.domain;

/**
 * minLon,minLat,maxLon,maxLat. When minLon is greater than maxLon the box crosses the 180
 * degree meridian (RFC 7946, section 5.2): it covers minLon up to 180 and -180 up to maxLon.
 */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

    public boolean crossesAntimeridian() {
        return minLon > maxLon;
    }
}
