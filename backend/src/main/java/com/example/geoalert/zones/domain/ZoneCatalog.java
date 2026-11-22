package com.example.geoalert.zones.domain;

import java.util.List;
import java.util.Set;

/** Port: the zones, which are static reference data loaded at start-up. */
public interface ZoneCatalog {

    /** Ids of every zone, restricted or advisory, that contains the point. */
    Set<String> zonesContaining(double lat, double lon);

    /** Only restricted zones raise alerts; advisory zones are shown and never alert. */
    boolean isRestricted(String zoneId);

    /** Every zone, for display. */
    List<Zone> zones();
}
