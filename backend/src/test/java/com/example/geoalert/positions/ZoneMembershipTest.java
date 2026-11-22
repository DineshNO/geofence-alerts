package com.example.geoalert.positions;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.geoalert.support.FeedClient;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The zones that contain an asset's current position are stored with it and move with it. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ZoneMembershipTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private FeedClient feed;

    @BeforeEach
    void signIn() throws Exception {
        feed = FeedClient.signIn(mvc, mapper);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private List<String> zonesOf(String assetId) {
        String joined =
                jdbc.queryForObject(
                        "SELECT array_to_string(zone_ids, ',') FROM asset_current WHERE asset_id = ?",
                        String.class,
                        assetId);
        return joined.isEmpty() ? List.of() : Arrays.stream(joined.split(",")).sorted().toList();
    }

    @Test
    void theZoneIsStoredWithThePosition() throws Exception {
        feed.send(line("zm-1", "ZMA", at(1), 38.2, 168.4)); // inside Z-01
        assertThat(zonesOf("ZMA")).containsExactly("Z-01");
    }

    @Test
    void anAssetOutsideEveryZoneHasNone() throws Exception {
        feed.send(line("zm-2", "ZMB", at(1), 10.0, 20.0));
        assertThat(zonesOf("ZMB")).isEmpty();
    }

    @Test
    void anAssetInAnOverlapIsInBothZones() throws Exception {
        feed.send(line("zm-3", "ZMC", at(1), 32.6, 174.8));
        assertThat(zonesOf("ZMC")).containsExactly("Z-04", "Z-05");
    }

    @Test
    void anAdvisoryZoneIsStoredToo() throws Exception {
        // Z-09 is advisory: lon 169.5-170.3, lat 40.5-41.2
        feed.send(line("zm-6", "ZMF", at(1), 40.85, 169.9));
        assertThat(zonesOf("ZMF")).containsExactly("Z-09");
    }

    @Test
    void theZonesFollowThePositionAsTheAssetMoves() throws Exception {
        feed.send(line("zm-4a", "ZMD", at(1), 38.2, 168.4)); // in Z-01
        feed.send(line("zm-4b", "ZMD", at(2), 10.0, 20.0)); // out
        assertThat(zonesOf("ZMD")).isEmpty();
        feed.send(line("zm-4c", "ZMD", at(3), 34.5, -179.8)); // across the meridian, Z-08
        assertThat(zonesOf("ZMD")).containsExactly("Z-08");
    }

    @Test
    void aLateReportNeverChangesTheStoredZones() throws Exception {
        feed.send(line("zm-5a", "ZME", at(10), 38.2, 168.4)); // in Z-01
        feed.send(line("zm-5b", "ZME", at(5), 10.0, 20.0)); // late, outside every zone
        assertThat(zonesOf("ZME")).containsExactly("Z-01");
    }
}
