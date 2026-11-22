package com.example.geoalert.alerts.adapter.jdbc;

import com.example.geoalert.alerts.domain.Alert;
import com.example.geoalert.alerts.domain.AlertCommandStore;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAlertCommandStore implements AlertCommandStore {

    private final JdbcTemplate jdbc;

    public JdbcAlertCommandStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** FOR UPDATE: concurrent commands on one alert queue here and each sees the latest state. */
    @Override
    public Optional<Alert> lockById(long id) {
        return jdbc
                .query(
                        "SELECT " + AlertRows.COLUMNS + " FROM alerts WHERE id = ? FOR UPDATE",
                        AlertRows::map,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Alert claim(long id, String username) {
        return jdbc.queryForObject(
                "UPDATE alerts SET status = 'CLAIMED', claimed_by = ?, claimed_at = now()"
                        + " WHERE id = ? RETURNING "
                        + AlertRows.COLUMNS,
                AlertRows::map,
                username,
                id);
    }

    @Override
    public Alert release(long id) {
        return jdbc.queryForObject(
                "UPDATE alerts SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL"
                        + " WHERE id = ? RETURNING "
                        + AlertRows.COLUMNS,
                AlertRows::map,
                id);
    }

    @Override
    public Alert resolve(long id, String username, String note) {
        return jdbc.queryForObject(
                "UPDATE alerts SET status = 'RESOLVED', resolved_by = ?, resolved_at = now(),"
                        + " resolution_note = ? WHERE id = ? RETURNING "
                        + AlertRows.COLUMNS,
                AlertRows::map,
                username,
                note,
                id);
    }
}
