package io.geoalert.alerts.adapter.jdbc;

import io.geoalert.alerts.domain.AlertStore;
import io.geoalert.alerts.domain.NewAlert;
import io.geoalert.common.jdbc.Sql;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAlertStore implements AlertStore {

    private static final int COLUMNS = 4;

    private final JdbcTemplate jdbc;

    public JdbcAlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Created> insertNew(List<NewAlert> alerts) {
        if (alerts.isEmpty()) {
            return List.of();
        }
        // ON CONFLICT names the partial unique index of R3, so an active alert for the same
        // asset and zone is skipped, and only really created alerts are returned
        String sql =
                "INSERT INTO alerts (asset_id, zone_id, status, trigger_report_id,"
                        + " trigger_observed_at) VALUES "
                        + String.join(
                                ",",
                                java.util.Collections.nCopies(
                                        alerts.size(), "(?,?,'OPEN',?,?)"))
                        + " ON CONFLICT (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED')"
                        + " DO NOTHING RETURNING id, asset_id, zone_id";
        List<Object> args = new ArrayList<>(alerts.size() * COLUMNS);
        for (NewAlert a : alerts) {
            args.add(a.assetId());
            args.add(a.zoneId());
            args.add(a.triggerReportId());
            args.add(Sql.utc(a.triggerObservedAt()));
        }
        Map<String, NewAlert> byKey = new HashMap<>();
        for (NewAlert a : alerts) {
            byKey.putIfAbsent(a.assetId() + "|" + a.zoneId(), a);
        }
        return jdbc.query(
                sql,
                (rs, row) ->
                        new Created(
                                rs.getLong("id"),
                                byKey.get(rs.getString("asset_id") + "|" + rs.getString("zone_id"))),
                args.toArray());
    }
}
