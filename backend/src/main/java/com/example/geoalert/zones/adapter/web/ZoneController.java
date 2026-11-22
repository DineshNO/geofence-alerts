package com.example.geoalert.zones.adapter.web;

import com.example.geoalert.assets.domain.AssetPosition;
import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.common.web.Limits;
import com.example.geoalert.common.web.OpaqueCursor;
import com.example.geoalert.zones.application.ZoneQueryService;
import com.example.geoalert.zones.domain.Zone;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Added to the fixed contract: the zones for the map, and which assets are in which zone. */
@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    public record ZoneItem(
            String zoneId,
            String name,
            String zoneClass,
            boolean crossesAntimeridian,
            Object geometry) {}

    public record ZoneList(List<ZoneItem> items) {}

    public record OccupancyItem(String zoneId, long assetCount) {}

    public record OccupancyList(List<OccupancyItem> items) {}

    public record ZoneAssets(List<AssetPosition> items, String nextCursor) {}

    private final ZoneQueryService zones;

    public ZoneController(ZoneQueryService zones) {
        this.zones = zones;
    }

    /** The zones with their shapes. They are static reference data. */
    @GetMapping
    public ZoneList list() {
        return new ZoneList(zones.zones().stream().map(ZoneController::item).toList());
    }

    /** How many assets are inside each zone now; a zone with none shows 0. */
    @GetMapping("/occupancy")
    public OccupancyList occupancy() {
        Map<String, Long> counts = zones.counts();
        return new OccupancyList(
                zones.zones().stream()
                        .map(z -> new OccupancyItem(z.id(), counts.getOrDefault(z.id(), 0L)))
                        .toList());
    }

    /** The assets currently inside one zone, by asset id, one bounded page at a time. */
    @GetMapping("/{zoneId}/assets")
    public ZoneAssets assets(
            @PathVariable String zoneId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int max = Limits.check(limit, DEFAULT_LIMIT, MAX_LIMIT);
        String after = OpaqueCursor.decode(cursor);
        zones.zone(zoneId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no such zone."));
        List<AssetPosition> rows = zones.assetsIn(zoneId, after, max);
        boolean more = rows.size() > max;
        List<AssetPosition> page = more ? rows.subList(0, max) : rows;
        String next = more ? OpaqueCursor.encode(page.get(page.size() - 1).assetId()) : null;
        return new ZoneAssets(page, next);
    }

    private static ZoneItem item(Zone z) {
        return new ZoneItem(
                z.id(), z.name(), z.zoneClass().name(), z.crossesAntimeridian(), z.geometry());
    }
}
