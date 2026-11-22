package io.geoalert.zones.domain;

/** RESTRICTED zones raise alerts; ADVISORY zones are shown on the map and never alert. */
public enum ZoneClass {
    RESTRICTED,
    ADVISORY
}
