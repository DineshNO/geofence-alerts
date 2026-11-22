package com.example.geoalert.stats.adapter.jdbc;

import com.example.geoalert.stats.domain.Stats;
import com.example.geoalert.stats.domain.StatsQueries;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The totals are sums of the append-only batch log, so parallel batches never wait on a shared
 * counter. The sum scans the log, which is small here; a periodic checkpoint is what would bound it
 * at a much larger scale (see the README).
 */
@Repository
public class JdbcStatsQueries implements StatsQueries {

    private final JdbcTemplate jdbc;

    public JdbcStatsQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Stats read() {
        long[] totals =
                jdbc.queryForObject(
                        "SELECT coalesce(sum(received), 0), coalesce(sum(accepted), 0),"
                                + " coalesce(sum(duplicates), 0), coalesce(sum(rejected), 0)"
                                + " FROM ingest_batches",
                        (rs, row) ->
                                new long[] {
                                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)
                                });
        long assets = jdbc.queryForObject("SELECT count(*) FROM asset_current", Long.class);
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query(
                "SELECT status, count(*) AS n FROM alerts GROUP BY status",
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("n"));
                });
        return new Stats(
                totals[0],
                totals[1],
                totals[2],
                totals[3],
                assets,
                new Stats.AlertCounts(
                        byStatus.getOrDefault("OPEN", 0L),
                        byStatus.getOrDefault("CLAIMED", 0L),
                        byStatus.getOrDefault("RESOLVED", 0L)));
    }
}
