package io.geoalert.positions.adapter.jdbc;

import io.geoalert.common.jdbc.Sql;
import io.geoalert.positions.domain.AssetCurrentStore;
import io.geoalert.positions.domain.AssetState;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssetCurrentStore implements AssetCurrentStore {

    private static final String COLUMNS =
            "asset_id, report_id, observed_at, lat, lon, kind, speed_kn, heading_deg, source,"
                    + " array_to_string(zone_ids, ',') AS zones";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcAssetCurrentStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * One INSERT for all new assets, in asset id order so that parallel batches take their
     * locks in the same order. ON CONFLICT DO NOTHING RETURNING tells which assets this
     * statement really created: those are the new ones.
     */
    @Override
    public Set<String> insertMissing(List<AssetState> states) {
        if (states.isEmpty()) {
            return Set.of();
        }
        String row = "(?,?,?,?,?,?,?,?,?,string_to_array(?, ','))";
        String sql =
                "INSERT INTO asset_current (asset_id, report_id, observed_at, lat, lon, kind,"
                        + " speed_kn, heading_deg, source, zone_ids) VALUES "
                        + String.join(",", java.util.Collections.nCopies(states.size(), row))
                        + " ON CONFLICT (asset_id) DO NOTHING RETURNING asset_id";
        List<Object> args = new ArrayList<>();
        for (AssetState s : states) {
            args.add(s.assetId());
            args.add(s.reportId());
            args.add(Sql.utc(s.observedAt()));
            args.add(s.lat());
            args.add(s.lon());
            args.add(s.kind());
            args.add(s.speedKn());
            args.add(s.headingDeg());
            args.add(s.source());
            args.add(joinZones(s.zoneIds()));
        }
        return new HashSet<>(jdbc.queryForList(sql, String.class, args.toArray()));
    }

    /** SELECT ... ORDER BY asset_id FOR UPDATE: the rows are locked in id order. */
    @Override
    public Map<String, AssetState> lockExisting(Collection<String> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, AssetState> result = new LinkedHashMap<>();
        named.query(
                "SELECT "
                        + COLUMNS
                        + " FROM asset_current WHERE asset_id IN (:ids)"
                        + " ORDER BY asset_id FOR UPDATE",
                new MapSqlParameterSource("ids", assetIds),
                rs -> {
                    AssetState state = map(rs);
                    result.put(state.assetId(), state);
                });
        return result;
    }

    /**
     * The row is only moved forward: the guard in the WHERE clause is R2 written in SQL, on top
     * of the row lock the caller already holds.
     */
    @Override
    public void update(List<AssetState> states) {
        if (states.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "UPDATE asset_current SET report_id = ?, observed_at = ?, lat = ?, lon = ?,"
                        + " kind = ?, speed_kn = ?, heading_deg = ?, source = ?,"
                        + " zone_ids = string_to_array(?, ',')"
                        + " WHERE asset_id = ? AND observed_at < ?",
                states,
                states.size(),
                (ps, s) -> {
                    ps.setString(1, s.reportId());
                    ps.setObject(2, Sql.utc(s.observedAt()));
                    ps.setDouble(3, s.lat());
                    ps.setDouble(4, s.lon());
                    ps.setString(5, s.kind());
                    setDouble(ps, 6, s.speedKn());
                    setDouble(ps, 7, s.headingDeg());
                    ps.setString(8, s.source());
                    ps.setString(9, joinZones(s.zoneIds()));
                    ps.setString(10, s.assetId());
                    ps.setObject(11, Sql.utc(s.observedAt()));
                });
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static String joinZones(Set<String> zoneIds) {
        return String.join(",", new TreeSet<>(zoneIds));
    }

    static AssetState map(java.sql.ResultSet rs) throws SQLException {
        String zones = rs.getString("zones");
        Set<String> zoneIds =
                zones == null || zones.isEmpty()
                        ? Set.of()
                        : new HashSet<>(Arrays.asList(zones.split(",")));
        return new AssetState(
                rs.getString("asset_id"),
                rs.getString("report_id"),
                rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getString("kind"),
                rs.getObject("speed_kn", Double.class),
                rs.getObject("heading_deg", Double.class),
                rs.getString("source"),
                zoneIds);
    }
}
