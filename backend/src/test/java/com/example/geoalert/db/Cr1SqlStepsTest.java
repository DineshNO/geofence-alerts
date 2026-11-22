package com.example.geoalert.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.geoalert.assets.domain.SourceType;
import com.example.geoalert.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The SQL procedures of CR-1 (deploy/sql), run on a scratch copy of the reports table so the
 * other tests are untouched: the backfill, its restart, the NOT NULL steps and the index.
 */
class Cr1SqlStepsTest extends PostgresIntegrationTest {

    private static final Path SQL = Path.of("..", "deploy", "sql");
    private static final String[] SOURCES = {"SAT-1", "GND-2", "MOB-3", "XYZ-4", null, "", "sat-1", "SAT"};

    private static String file(String name) throws Exception {
        return Files.readString(SQL.resolve(name));
    }

    private static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        for (String part : script.replaceAll("(?m)^--.*$", "").split(";\\s*\\n")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private Connection scratch() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS cr1 CASCADE");
            s.execute("CREATE SCHEMA cr1");
            s.execute("CREATE TABLE cr1.reports (LIKE public.reports INCLUDING ALL)");
        }
        Connection c =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl() + "&currentSchema=cr1".replace("&", POSTGRES.getJdbcUrl().contains("?") ? "&" : "?"),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        c.setAutoCommit(true);
        return c;
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @AfterEach
    void dropScratchSchema() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS cr1 CASCADE");
        }
    }

    @Test
    void backfillFinalizeAndIndexWorkOnOldRows() throws Exception {
        try (Connection c = scratch();
                Statement s = c.createStatement()) {
            int rows = 2500;
            for (int i = 0; i < rows; i++) {
                String source = SOURCES[i % SOURCES.length];
                s.execute(
                        "INSERT INTO reports (report_id, asset_id, observed_at, lat, lon, source) VALUES ('r"
                                + i + "', 'A" + (i % 7) + "', now(), 1, 1, "
                                + (source == null ? "NULL" : "'" + source + "'") + ")");
            }
            // some rows were written by version 1.1.0 already and must stay as they are
            s.execute("UPDATE reports SET source_type = 'MOBILE' WHERE report_id = 'r0'");

            s.execute(file("10-backfill-procedure.sql"));

            // a first, partial run is not possible to interrupt here, so run with tiny batches
            s.execute("CALL backfill_source_type(300, 0)");
            assertThat(count(s, "SELECT count(*) FROM reports WHERE source_type IS NULL")).isZero();
            assertThat(count(s, "SELECT rows_done FROM backfill_progress")).isEqualTo(rows - 1);
            assertThat(count(s, "SELECT count(*) FROM reports WHERE report_id = 'r0' AND source_type = 'MOBILE'"))
                    .isEqualTo(1);

            // every value equals the application's rule
            try (var rs = s.executeQuery("SELECT source, source_type FROM reports WHERE report_id <> 'r0'")) {
                while (rs.next()) {
                    assertThat(rs.getString(2)).isEqualTo(SourceType.derive(rs.getString(1)).name());
                }
            }

            // a restart changes nothing; new rows written afterwards by an old instance are caught
            s.execute("CALL backfill_source_type(300, 0)");
            assertThat(count(s, "SELECT rows_done FROM backfill_progress")).isEqualTo(rows - 1);
            s.execute("INSERT INTO reports (report_id, asset_id, observed_at, lat, lon, source) VALUES ('late', 'A1', now(), 1, 1, 'GND-1')");
            s.execute("CALL backfill_source_type(300, 0)");
            assertThat(count(s, "SELECT count(*) FROM reports WHERE report_id = 'late' AND source_type = 'GROUND'"))
                    .isEqualTo(1);

            // the NOT NULL steps: refused while a NULL row exists, accepted after the backfill
            s.execute("INSERT INTO reports (report_id, asset_id, observed_at, lat, lon, source) VALUES ('stray', 'A1', now(), 1, 1, 'SAT-9')");
            assertThatThrownBy(() -> {
                        for (String st : statements(file("30-not-null.sql"))) {
                            s.execute(st);
                        }
                    })
                    .isInstanceOf(SQLException.class);
            s.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS reports_source_type_nn");
            s.execute("CALL backfill_source_type(300, 0)");
            for (String st : statements(file("30-not-null.sql"))) {
                s.execute(st);
            }
            assertThat(count(s, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'cr1'"
                            + " AND table_name = 'reports' AND column_name = 'source_type' AND is_nullable = 'NO'"))
                    .isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM pg_constraint WHERE conname = 'reports_source_type_nn'")).isZero();
            // an old-style insert (no source_type) is now refused
            assertThatThrownBy(() -> s.execute("INSERT INTO reports (report_id, asset_id, observed_at, lat, lon) VALUES ('old', 'A1', now(), 1, 1)"))
                    .isInstanceOf(SQLException.class);

            // the index (a single statement, outside a transaction)
            s.execute(file("40-index.sql"));
            assertThat(count(s, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'cr1'"
                            + " AND indexname = 'reports_asset_type_observed_idx'"))
                    .isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                            + " WHERE c.relname = 'reports_asset_type_observed_idx' AND i.indisvalid"))
                    .isEqualTo(1);
        }
    }
}
