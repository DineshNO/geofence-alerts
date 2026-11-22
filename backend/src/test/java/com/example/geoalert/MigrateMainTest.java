package com.example.geoalert;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.geoalert.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** The one-shot migrate step: an empty database becomes the current schema, and running it again is harmless. */
class MigrateMainTest {

    @Test
    void anEmptyDatabaseGetsTheWholeSchemaAndASecondRunChangesNothing() throws Exception {
        try (PostgreSQLContainer<?> db = new PostgreSQLContainer<>(PostgresIntegrationTest.POSTGRES_IMAGE)) {
            db.start();
            MigrateMain.migrate(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            List<String> first = tables(db);
            assertThat(first)
                    .contains(
                            "reports",
                            "asset_current",
                            "ingest_batches",
                            "rejected_lines",
                            "alerts",
                            "alert_audit",
                            "users",
                            "databasechangelog");

            MigrateMain.migrate(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            assertThat(tables(db)).isEqualTo(first);
            assertThat(appliedChangesets(db)).isEqualTo(7);
        }
    }

    private static List<String> tables(PostgreSQLContainer<?> db) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                Statement st = c.createStatement();
                ResultSet rs =
                        st.executeQuery(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public' ORDER BY 1")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static int appliedChangesets(PostgreSQLContainer<?> db) throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM databasechangelog")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
