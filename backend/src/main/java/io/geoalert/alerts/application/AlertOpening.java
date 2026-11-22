package io.geoalert.alerts.application;

import io.geoalert.alerts.domain.AlertStore;
import io.geoalert.alerts.domain.AuditLog;
import io.geoalert.alerts.domain.NewAlert;
import io.geoalert.positions.domain.PositionRule.ZoneEntry;
import io.geoalert.stream.domain.AlertEvent;
import io.geoalert.stream.domain.AlertEventBus;
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
    private final AlertEventBus events;

    public AlertOpening(AlertStore store, AuditLog audit, AlertEventBus events) {
        this.store = store;
        this.audit = audit;
        this.events = events;
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
        // the live event goes out with the commit, and never if the batch rolls back
        events.publish(
                created.stream()
                        .map(
                                c ->
                                        new AlertEvent(
                                                "OPENED",
                                                Long.toString(c.id()),
                                                "OPEN",
                                                c.alert().assetId(),
                                                c.alert().zoneId()))
                        .toList());
        return created;
    }
}
