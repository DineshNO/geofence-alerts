package com.example.geoalert.assets.adapter.jdbc;

import com.example.geoalert.assets.domain.AssetPosition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/** The current-position columns and how a row becomes an {@link AssetPosition}. */
public final class AssetRows {

    public static final String COLUMNS =
            "asset_id, kind, report_id, observed_at, lat, lon, speed_kn, heading_deg, source";

    private AssetRows() {}

    public static AssetPosition map(ResultSet rs, int row) throws SQLException {
        return new AssetPosition(
                rs.getString("asset_id"),
                rs.getString("kind"),
                rs.getString("report_id"),
                rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getObject("speed_kn", Double.class),
                rs.getObject("heading_deg", Double.class),
                rs.getString("source"));
    }
}
