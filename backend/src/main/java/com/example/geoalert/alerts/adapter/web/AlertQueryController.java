package com.example.geoalert.alerts.adapter.web;

import com.example.geoalert.alerts.application.AlertQueryService;
import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertQueries;
import com.example.geoalert.alerts.domain.AlertStatus;
import com.example.geoalert.alerts.domain.AuditEntry;
import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.common.web.Limits;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertQueryController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    public record AlertPage(List<AlertResponse> items, String nextCursor) {}

    public record AuditItem(
            Instant at, String actor, String action, String fromStatus, String toStatus) {}

    public record AuditResponse(List<AuditItem> items) {}

    private final AlertQueryService alerts;

    public AlertQueryController(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    /** Oldest first, one bounded page at a time. Follow nextCursor until it is null. */
    @GetMapping
    public AlertPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String zoneId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int max = Limits.check(limit, DEFAULT_LIMIT, MAX_LIMIT);
        long after = AlertCursor.decode(cursor);
        AlertQueries.Filter filter = new AlertQueries.Filter(parseStatus(status), assetId, zoneId);

        List<Alert> rows = alerts.page(filter, after, max);
        boolean more = rows.size() > max;
        List<Alert> page = more ? rows.subList(0, max) : rows;
        String next = more ? AlertCursor.encode(page.get(page.size() - 1).id()) : null;
        return new AlertPage(page.stream().map(AlertResponse::of).toList(), next);
    }

    @GetMapping("/{alertId}")
    public AlertResponse one(@PathVariable String alertId) {
        return AlertResponse.of(alerts.find(parseId(alertId)).orElseThrow(this::notFound));
    }

    /** Every state change of one alert, oldest first. */
    @GetMapping("/{alertId}/audit")
    public AuditResponse auditTrail(@PathVariable String alertId) {
        long id = parseId(alertId);
        if (!alerts.exists(id)) {
            throw notFound();
        }
        List<AuditItem> items =
                alerts.auditTrail(id).stream().map(AlertQueryController::item).toList();
        return new AuditResponse(items);
    }

    private static AuditItem item(AuditEntry e) {
        return new AuditItem(
                e.at(),
                e.actor(),
                e.action().name(),
                e.fromStatus() == null ? null : e.fromStatus().name(),
                e.toStatus().name());
    }

    private static AlertStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return AlertStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "status must be OPEN, CLAIMED or RESOLVED.");
        }
    }

    private static long parseId(String alertId) {
        try {
            return Long.parseLong(alertId);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no such alert.");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no such alert.");
    }
}
