package com.example.geoalert.alerts.adapter.jdbc;

import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertQueries;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAlertQueries implements AlertQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAlertQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Alert> page(Filter filter, long afterId, int limit) {
        StringBuilder sql =
                new StringBuilder("SELECT " + AlertRows.COLUMNS + " FROM alerts WHERE id > :after");
        MapSqlParameterSource params = new MapSqlParameterSource("after", afterId);
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.assetId() != null) {
            sql.append(" AND asset_id = :asset");
            params.addValue("asset", filter.assetId());
        }
        if (filter.zoneId() != null) {
            sql.append(" AND zone_id = :zone");
            params.addValue("zone", filter.zoneId());
        }
        sql.append(" ORDER BY id LIMIT :limit");
        params.addValue("limit", limit + 1);
        return jdbc.query(sql.toString(), params, AlertRows::map);
    }

    @Override
    public Optional<Alert> find(long id) {
        return jdbc
                .query(
                        "SELECT " + AlertRows.COLUMNS + " FROM alerts WHERE id = :id",
                        new MapSqlParameterSource("id", id),
                        AlertRows::map)
                .stream()
                .findFirst();
    }

    @Override
    public boolean exists(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM alerts WHERE id = :id)",
                        new MapSqlParameterSource("id", id),
                        Boolean.class));
    }
}
