package com.example.geoalert.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.geoalert.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class IngestSchemaTest extends PostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void changesetsCreateTheIngestTables() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = 'public'",
                        String.class);
        assertThat(tables)
                .contains("reports", "asset_current", "ingest_batches", "rejected_lines");
    }

    @Test
    void theReportIdIsUnique() {
        String sql =
                "INSERT INTO reports (report_id, asset_id, observed_at, lat, lon)"
                        + " VALUES (?, 'AST-1', now(), 1.0, 2.0)";
        jdbc.update(sql, "unique-check-1");
        assertThatThrownBy(() -> jdbc.update(sql, "unique-check-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theCurrentPositionTableLeavesRoomForInPlaceUpdates() {
        String options =
                jdbc.queryForObject(
                        "SELECT array_to_string(reloptions, ',') FROM pg_class"
                                + " WHERE relname = 'asset_current'",
                        String.class);
        assertThat(options).contains("fillfactor=70");
    }

    @Test
    void theTrackIndexServesOneAssetOldestFirst() {
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'reports'",
                        String.class);
        assertThat(indexes)
                .anyMatch(def -> def.contains("(asset_id, observed_at, id)"))
                .anyMatch(def -> def.contains("UNIQUE") && def.contains("(report_id)"));
    }
}
