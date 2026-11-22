package io.geoalert.zones.application;

import io.geoalert.assets.domain.AssetPosition;
import io.geoalert.zones.domain.Zone;
import io.geoalert.zones.domain.ZoneCatalog;
import io.geoalert.zones.domain.ZoneOccupancyQueries;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The zones for display, and which assets are inside them. */
@Service
public class ZoneQueryService {

    private final ZoneCatalog catalog;
    private final ZoneOccupancyQueries occupancy;

    public ZoneQueryService(ZoneCatalog catalog, ZoneOccupancyQueries occupancy) {
        this.catalog = catalog;
        this.occupancy = occupancy;
    }

    public List<Zone> zones() {
        return catalog.zones();
    }

    public Optional<Zone> zone(String zoneId) {
        return catalog.zones().stream().filter(z -> z.id().equals(zoneId)).findFirst();
    }

    public Map<String, Long> counts() {
        return occupancy.counts();
    }

    public List<AssetPosition> assetsIn(String zoneId, String afterAssetId, int limit) {
        return occupancy.assetsIn(zoneId, afterAssetId, limit);
    }
}
