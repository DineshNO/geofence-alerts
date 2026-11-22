package com.example.geoalert.alerts.domain;

import java.util.List;
import java.util.Optional;

/** Port: reading alerts and their audit trail. */
public interface AlertQueries {

    /** Filters of the list; null means no filter on that field. */
    record Filter(AlertStatus status, String assetId, String zoneId) {}

    /**
     * Alerts with an id above {@code afterId}, oldest first, at most {@code limit + 1} rows (the
     * extra row tells the caller there is another page). Keyset paging: every page costs the same
     * and following the cursors returns every alert exactly once.
     */
    List<Alert> page(Filter filter, long afterId, int limit);

    Optional<Alert> find(long id);

    boolean exists(long id);
}
