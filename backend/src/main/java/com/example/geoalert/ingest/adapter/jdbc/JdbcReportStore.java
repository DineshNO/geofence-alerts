package com.example.geoalert.ingest.adapter.jdbc;

import com.example.geoalert.assets.domain.SourceType;
import com.example.geoalert.common.jdbc.Sql;
import com.example.geoalert.ingest.domain.ParsedReport;
import com.example.geoalert.ingest.domain.ReportStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportStore implements ReportStore {

    private static final int COLUMNS = 10;

    private final JdbcTemplate jdbc;

    public JdbcReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One multi-row INSERT for the whole batch. ON CONFLICT DO NOTHING skips a report id that
     * is already stored, and RETURNING gives back only the rows that were really inserted, so
     * the caller knows exactly which reports are new (R1). The unique index decides between
     * parallel requests: a conflicting insert waits for the other to commit, then skips.
     *
     * <p>Since 1.1.0 (CR-1) every new row also stores its source type, derived from the source.
     *
     * <p>The rows go in report id order. The feed repeats reports across batches, and two inserts that
     * take the same unique keys in opposite order wait for each other in a circle (a deadlock,
     * SQLSTATE 40P01). One fixed order for every transaction makes that impossible.
     */
    @Override
    public Set<String> insertNew(List<ParsedReport> unordered) {
        List<ParsedReport> reports = new ArrayList<>(unordered);
        reports.sort(Comparator.comparing(ParsedReport::reportId));
        String sql =
                "INSERT INTO reports (report_id, asset_id, observed_at, lat, lon, kind,"
                        + " speed_kn, heading_deg, source, source_type) VALUES "
                        + Sql.valueRows(reports.size(), COLUMNS)
                        + " ON CONFLICT (report_id) DO NOTHING RETURNING report_id";
        List<Object> args = new ArrayList<>(reports.size() * COLUMNS);
        for (ParsedReport r : reports) {
            args.add(r.reportId());
            args.add(r.assetId());
            args.add(Sql.utc(r.observedAt()));
            args.add(r.lat());
            args.add(r.lon());
            args.add(r.kind());
            args.add(r.speedKn());
            args.add(r.headingDeg());
            args.add(r.source());
            args.add(SourceType.derive(r.source()).name());
        }
        return new HashSet<>(jdbc.queryForList(sql, String.class, args.toArray()));
    }
}
