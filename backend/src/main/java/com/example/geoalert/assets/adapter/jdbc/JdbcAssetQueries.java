package com.example.geoalert.assets.adapter.jdbc;

import com.example.geoalert.assets.domain.AssetPosition;
import com.example.geoalert.assets.domain.AssetQueries;
import com.example.geoalert.assets.domain.BoundingBox;
import com.example.geoalert.assets.domain.SourceType;
import com.example.geoalert.assets.domain.TrackPoint;
import com.example.geoalert.common.jdbc.Sql;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssetQueries implements AssetQueries {

    private static final String POSITION_COLUMNS = AssetRows.COLUMNS;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAssetQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The map's viewport query. asset_current is a small table (one row per asset), so a scan
     * with these filters is a fraction of a millisecond. A box across the 180 degree meridian
     * is two longitude ranges.
     */
    @Override
    public List<AssetPosition> inBox(BoundingBox box, int limit) {
        String longitude =
                box.crossesAntimeridian()
                        ? "(lon >= :minLon OR lon <= :maxLon)"
                        : "lon BETWEEN :minLon AND :maxLon";
        return jdbc.query(
                "SELECT "
                        + POSITION_COLUMNS
                        + " FROM asset_current WHERE lat BETWEEN :minLat AND :maxLat AND "
                        + longitude
                        + " ORDER BY asset_id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("minLon", box.minLon())
                        .addValue("maxLon", box.maxLon())
                        .addValue("minLat", box.minLat())
                        .addValue("maxLat", box.maxLat())
                        .addValue("limit", limit + 1),
                AssetRows::map);
    }

    @Override
    public Optional<AssetPosition> find(String assetId) {
        return jdbc
                .query(
                        "SELECT " + POSITION_COLUMNS + " FROM asset_current WHERE asset_id = :id",
                        new MapSqlParameterSource("id", assetId),
                        AssetRows::map)
                .stream()
                .findFirst();
    }

    /**
     * Served by the index on (asset_id, observed_at, id): one asset, oldest first, no sort.
     *
     * <p>Rows written before CR-1 (or not yet backfilled) have no stored type. They are read
     * with the type derived from their source, and the filter matches them the same way, so
     * the answer is the same before, during and after the backfill.
     */
    @Override
    public List<TrackPoint> track(
            String assetId, Instant from, Instant to, SourceType sourceType, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT report_id, observed_at, lat, lon, source,"
                                + " COALESCE(source_type, "
                                + SourceType.SQL_DERIVE
                                + ") AS source_type FROM reports WHERE asset_id = :asset");
        MapSqlParameterSource params = new MapSqlParameterSource("asset", assetId);
        if (from != null) {
            sql.append(" AND observed_at >= :from");
            params.addValue("from", Sql.utc(from));
        }
        if (to != null) {
            sql.append(" AND observed_at <= :to");
            params.addValue("to", Sql.utc(to));
        }
        if (sourceType != null) {
            sql.append(
                    " AND COALESCE(source_type, " + SourceType.SQL_DERIVE + ") = :sourceType");
            params.addValue("sourceType", sourceType.name());
        }
        sql.append(" ORDER BY observed_at, id LIMIT :limit");
        params.addValue("limit", limit + 1);
        return jdbc.query(
                sql.toString(),
                params,
                (rs, row) ->
                        new TrackPoint(
                                rs.getString("report_id"),
                                rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                                rs.getDouble("lat"),
                                rs.getDouble("lon"),
                                rs.getString("source"),
                                SourceType.valueOf(rs.getString("source_type"))));
    }
}
