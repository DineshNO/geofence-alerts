package com.example.geoalert.zones.adapter;

import com.example.geoalert.zones.domain.ZoneCatalog;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Temporary stand-in until the zones are loaded (next step): no zone contains anything. */
@Component
class NoZoneCatalog implements ZoneCatalog {

    @Override
    public Set<String> zonesContaining(double lat, double lon) {
        return Set.of();
    }

    @Override
    public boolean isRestricted(String zoneId) {
        return false;
    }
}
