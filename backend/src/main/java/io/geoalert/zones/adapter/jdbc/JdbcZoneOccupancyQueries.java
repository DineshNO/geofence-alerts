package io.geoalert.zones.adapter.jdbc;

import io.geoalert.assets.adapter.jdbc.AssetRows;
import io.geoalert.assets.domain.AssetPosition;
import io.geoalert.zones.domain.ZoneOccupancyQueries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcZoneOccupancyQueries implements ZoneOccupancyQueries {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcZoneOccupancyQueries(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** One group-by over the small current-position table. */
    @Override
    public Map<String, Long> counts() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(
                "SELECT zone AS zone_id, count(*) AS n FROM asset_current, unnest(zone_ids) AS zone"
                        + " GROUP BY zone",
                rs -> {
                    counts.put(rs.getString("zone_id"), rs.getLong("n"));
                });
        return counts;
    }

    @Override
    public List<AssetPosition> assetsIn(String zoneId, String afterAssetId, int limit) {
        return named.query(
                "SELECT "
                        + AssetRows.COLUMNS
                        + " FROM asset_current WHERE :zone = ANY(zone_ids) AND asset_id > :after"
                        + " ORDER BY asset_id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("zone", zoneId)
                        .addValue("after", afterAssetId == null ? "" : afterAssetId)
                        .addValue("limit", limit + 1),
                AssetRows::map);
    }
}
