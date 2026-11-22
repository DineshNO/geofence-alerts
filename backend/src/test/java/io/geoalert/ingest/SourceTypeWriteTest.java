package io.geoalert.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.support.FeedClient;
import io.geoalert.support.PostgresIntegrationTest;
import io.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** CR-1: version 1.1.0 stores the source type of every report it accepts. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SourceTypeWriteTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static String report(String id, String source) {
        return "{\"reportId\":\"" + id + "\",\"assetId\":\"SWA\",\"kind\":\"SURFACE\","
                + "\"observedAt\":\"2026-11-02T06:00:00Z\",\"lat\":1.0,\"lon\":1.0"
                + (source == null ? "" : ",\"source\":\"" + source + "\"") + "}";
    }

    @Test
    void everyAcceptedReportStoresTheTypeDerivedFromItsSource() throws Exception {
        FeedClient.signIn(mvc, mapper)
                .send(
                        report("sw-1", "SAT-2"),
                        report("sw-2", "GND-7"),
                        report("sw-3", "MOB-1"),
                        report("sw-4", "XYZ-3"),
                        report("sw-5", null));
        Map<String, String> stored =
                jdbc.queryForList(
                                "SELECT report_id, source_type FROM reports WHERE report_id LIKE 'sw-%'")
                        .stream()
                        .collect(Collectors.toMap(r -> (String) r.get("report_id"), r -> (String) r.get("source_type")));
        assertThat(stored)
                .containsEntry("sw-1", "SATELLITE")
                .containsEntry("sw-2", "GROUND")
                .containsEntry("sw-3", "MOBILE")
                .containsEntry("sw-4", "UNKNOWN")
                .containsEntry("sw-5", "UNKNOWN");
        assertThat(List.copyOf(stored.values())).doesNotContainNull();
    }
}
