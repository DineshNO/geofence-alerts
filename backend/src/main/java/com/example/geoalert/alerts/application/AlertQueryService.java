package com.example.geoalert.alerts.application;

import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertQueries;
import com.example.geoalert.alerts.domain.AuditEntry;
import com.example.geoalert.alerts.domain.AuditLog;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Reads alerts and their audit trail. */
@Service
public class AlertQueryService {

    private final AlertQueries queries;
    private final AuditLog audit;

    public AlertQueryService(AlertQueries queries, AuditLog audit) {
        this.queries = queries;
        this.audit = audit;
    }

    public List<Alert> page(AlertQueries.Filter filter, long afterId, int limit) {
        return queries.page(filter, afterId, limit);
    }

    public Optional<Alert> find(long id) {
        return queries.find(id);
    }

    public boolean exists(long id) {
        return queries.exists(id);
    }

    public List<AuditEntry> auditTrail(long id) {
        return audit.forAlert(id);
    }
}
