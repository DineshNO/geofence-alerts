package io.geoalert.positions.application;

import io.geoalert.ingest.domain.ParsedReport;
import io.geoalert.positions.domain.AssetCurrentStore;
import io.geoalert.positions.domain.AssetState;
import io.geoalert.positions.domain.PositionRule;
import io.geoalert.positions.domain.PositionRule.ZoneEntry;
import io.geoalert.zones.domain.ZoneCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Brings the current positions up to date for the accepted reports of one batch. It runs inside
 * the batch transaction of the ingest service; it never opens one of its own.
 *
 * <p>Per asset, in sorted asset order (so parallel batches lock in the same order and cannot
 * deadlock): the asset's first report is inserted with ON CONFLICT DO NOTHING, which tells
 * whether the asset is new; an existing asset's row is locked with SELECT FOR UPDATE, so
 * concurrent reports for one asset queue. The asset's reports are then applied in memory in
 * delivery order and the final position is written once.
 */
@Service
public class PositionService {

    private final AssetCurrentStore store;
    private final PositionRule rule;

    public PositionService(AssetCurrentStore store, ZoneCatalog zones) {
        this.store = store;
        this.rule = new PositionRule(zones);
    }

    /** Returns the restricted zones that were entered, for the alert rule. */
    public List<ZoneEntry> apply(List<ParsedReport> accepted) {
        if (accepted.isEmpty()) {
            return List.of();
        }
        // asset id order for locking; delivery order is kept inside each asset
        Map<String, List<ParsedReport>> byAsset = new TreeMap<>();
        for (ParsedReport report : accepted) {
            byAsset.computeIfAbsent(report.assetId(), id -> new ArrayList<>()).add(report);
        }

        // 1. a new asset starts with its first report
        Map<String, AssetState> firstStates = new TreeMap<>();
        byAsset.forEach(
                (assetId, reports) ->
                        firstStates.put(
                                assetId, rule.apply(null, List.of(reports.get(0))).state()));
        Set<String> created = store.insertMissing(new ArrayList<>(firstStates.values()));

        // 2. an existing asset: lock its row and read where it is now
        List<String> existingIds = byAsset.keySet().stream().filter(id -> !created.contains(id)).toList();
        Map<String, AssetState> existing = store.lockExisting(existingIds);

        // 3. apply each asset's reports in memory, write the final position once
        List<ZoneEntry> entered = new ArrayList<>();
        List<AssetState> updates = new ArrayList<>();
        for (Map.Entry<String, List<ParsedReport>> entry : byAsset.entrySet()) {
            String assetId = entry.getKey();
            boolean isNew = created.contains(assetId);
            AssetState previous = isNew ? null : existing.get(assetId);
            if (!isNew && previous == null) {
                throw new IllegalStateException("The current position of an asset is missing.");
            }
            PositionRule.Result result = rule.apply(previous, entry.getValue());
            entered.addAll(result.entered());
            if (result.changed()) {
                boolean alreadyStored =
                        isNew
                                && result.state()
                                        .reportId()
                                        .equals(firstStates.get(assetId).reportId());
                if (!alreadyStored) {
                    updates.add(result.state());
                }
            }
        }
        store.update(updates);
        return entered;
    }
}
