package com.example.geoalert.alerts.application;

import com.example.geoalert.alerts.domain.AlertStore;
import com.example.geoalert.alerts.domain.AuditLog;
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
    private final AuditLog audit;

    public AlertOpening(AlertStore store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
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
        List<AlertStore.Created> created = store.insertNew(due);
        // R5: the OPENED entry is written with the alert, in the same transaction
        audit.recordOpened(created.stream().map(AlertStore.Created::id).toList());
        return created;
    }
}
