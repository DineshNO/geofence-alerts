package com.example.geoalert.positions.domain;

import com.example.geoalert.ingest.domain.ParsedReport;
import com.example.geoalert.zones.domain.ZoneCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The position rule and the alert rule for one asset, with no database and no framework.
 *
 * <p>R2: the current position is the accepted report with the newest observation time. A report
 * that is not newer than the current position is late: it is stored in the track by the caller
 * and here it neither moves the position nor raises an alert.
 *
 * <p>Alert rule: an alert is due for a restricted zone that contains the new position when
 * the previous position was outside that zone, or there was no previous position.
 */
public final class PositionRule {

    /** A restricted zone entered by the report that caused it. */
    public record ZoneEntry(ParsedReport trigger, String zoneId) {}

    public record Result(AssetState state, boolean changed, List<ZoneEntry> entered) {}

    private final ZoneCatalog zones;

    public PositionRule(ZoneCatalog zones) {
        this.zones = zones;
    }

    /**
     * Applies one asset's accepted reports, in the order they were delivered, to its previous
     * state (null when the asset has no position yet).
     */
    public Result apply(AssetState previous, List<ParsedReport> reports) {
        AssetState state = previous;
        boolean changed = false;
        List<ZoneEntry> entered = new ArrayList<>();
        for (ParsedReport report : reports) {
            if (state != null && !report.observedAt().isAfter(state.observedAt())) {
                continue; // late (or equal): track only
            }
            Set<String> nowIn = zones.zonesContaining(report.lat(), report.lon());
            for (String zoneId : nowIn) {
                boolean wasIn = state != null && state.zoneIds().contains(zoneId);
                if (!wasIn && zones.isRestricted(zoneId)) {
                    entered.add(new ZoneEntry(report, zoneId));
                }
            }
            state = AssetState.from(report, nowIn);
            changed = true;
        }
        return new Result(state, changed, List.copyOf(entered));
    }
}
