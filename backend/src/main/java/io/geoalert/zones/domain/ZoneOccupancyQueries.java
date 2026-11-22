package io.geoalert.zones.domain;

import io.geoalert.assets.domain.AssetPosition;
import java.util.List;
import java.util.Map;

/**
 * Port: which assets are inside which zone, from the zones stored with each current position when
 * the report was ingested. "Inside" means the last reported position (no interpolation), the same
 * as the alert rule.
 */
public interface ZoneOccupancyQueries {

    /** Asset count per zone id; zones with no asset are absent. */
    Map<String, Long> counts();

    /**
     * The assets currently inside a zone, ordered by asset id, after the given asset id (null
     * for the first page), at most {@code limit + 1} rows.
     */
    List<AssetPosition> assetsIn(String zoneId, String afterAssetId, int limit);
}
