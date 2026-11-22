package com.example.geoalert.alerts.application;

import com.example.geoalert.alerts.domain.AlertStore;
import com.example.geoalert.alerts.domain.NewAlert;
import com.example.geoalert.positions.domain.PositionRule.ZoneEntry;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Raises the alerts that the position rule found due. It runs inside the batch transaction of
 * the ingest service and never opens one of its own.
 */
@Service
public class AlertOpening {

    private final AlertStore store;

    public AlertOpening(AlertStore store) {
        this.store = store;
    }

    /** Returns the alerts that were really created (an active alert for the pair is skipped). */
    public List<AlertStore.Created> open(List<ZoneEntry> entered) {
        List<NewAlert> due =
                entered.stream()
                        .map(
                                e ->
                                        new NewAlert(
                                                e.trigger().assetId(),
                                                e.zoneId(),
                                                e.trigger().reportId(),
                                                e.trigger().observedAt()))
                        .toList();
        return store.insertNew(due);
    }
}
