package com.example.geoalert.alerts.application;

import com.example.geoalert.alerts.domain.Actor;
import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertCommandStore;
import com.example.geoalert.alerts.domain.AlertRules;
import com.example.geoalert.alerts.domain.AlertRules.Decision;
import com.example.geoalert.alerts.domain.AuditAction;
import com.example.geoalert.alerts.domain.AuditLog;
import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.stream.domain.AlertEvent;
import com.example.geoalert.stream.domain.AlertEventBus;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator commands. Each one is a single transaction: lock the alert row, read its state,
 * let the rules decide, then change it. Concurrent commands on one alert queue on the row lock and
 * each decides on the state the previous one left, so exactly one claim can succeed (R4).
 */
@Service
public class AlertCommandService {

    public static final int MAX_NOTE_LENGTH = 500;

    private final AlertCommandStore store;
    private final AuditLog audit;
    private final AlertEventBus events;
    private final TransactionTemplate tx;

    public AlertCommandService(
            AlertCommandStore store, AuditLog audit, AlertEventBus events, TransactionTemplate tx) {
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
    }

    public Alert claim(String alertId, Actor actor) {
        return command(
                alertId,
                actor,
                AuditAction.CLAIMED,
                AlertRules::claim,
                (a) -> store.claim(a.id(), actor.username()));
    }

    public Alert release(String alertId, Actor actor) {
        return command(
                alertId, actor, AuditAction.RELEASED, AlertRules::release, (a) -> store.release(a.id()));
    }

    public Alert resolve(String alertId, Actor actor, String note) {
        if (note == null || note.isBlank() || note.length() > MAX_NOTE_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "note is required and has at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return command(
                alertId,
                actor,
                AuditAction.RESOLVED,
                AlertRules::resolve,
                (a) -> store.resolve(a.id(), actor.username(), note));
    }

    private Alert command(
            String alertId,
            Actor actor,
            AuditAction action,
            BiFunction<Alert, Actor, Decision> rule,
            java.util.function.Function<Alert, Alert> apply) {
        long id = parseId(alertId);
        return tx.execute(
                status -> {
                    Alert current =
                            store.lockById(id)
                                    .orElseThrow(
                                            () ->
                                                    new ApiException(
                                                            HttpStatus.NOT_FOUND,
                                                            "NOT_FOUND",
                                                            "There is no such alert."));
                    return switch (rule.apply(current, actor)) {
                        case Decision.NoChange noChange -> current;
                        case Decision.Apply applied -> {
                            Alert updated = apply.apply(current);
                            // R5: the audit entry is written in this same transaction, so the
                            // change and its record are stored together or not at all
                            audit.record(
                                    updated.id(),
                                    actor.username(),
                                    action,
                                    current.status(),
                                    updated.status());
                            // and every operator's console is told, once the transaction commits
                            events.publish(
                                    List.of(
                                            new AlertEvent(
                                                    action.name(),
                                                    Long.toString(updated.id()),
                                                    updated.status().name(),
                                                    updated.assetId(),
                                                    updated.zoneId())));
                            yield updated;
                        }
                        case Decision.Refuse refuse -> throw refusal(refuse);
                    };
                });
    }

    private static long parseId(String alertId) {
        try {
            return Long.parseLong(alertId);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no such alert.");
        }
    }

    private static ApiException refusal(Decision.Refuse refuse) {
        return switch (refuse.reason()) {
            case ALREADY_CLAIMED ->
                    new ApiException(HttpStatus.CONFLICT, "ALERT_ALREADY_CLAIMED", refuse.message());
            case NOT_OWNER ->
                    new ApiException(HttpStatus.FORBIDDEN, "NOT_ALERT_OWNER", refuse.message());
            case INVALID_STATE ->
                    new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", refuse.message());
        };
    }
}
