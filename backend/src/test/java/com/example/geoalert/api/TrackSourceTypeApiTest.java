package com.example.geoalert.api;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.geoalert.support.FeedClient;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CR-1 on the read side. Rows written before the change have no stored type; the filter and
 * the answer must treat them as if the type were stored.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TrackSourceTypeApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private String operator;

    @BeforeEach
    void signIn() throws Exception {
        operator = Tokens.bearer(Tokens.login(mvc, mapper, OPERATOR_USER));
    }

    private void insertOld(String reportId, String asset, int second, String source, String type) {
        jdbc.update(
                "INSERT INTO reports (report_id, asset_id, observed_at, lat, lon, source, source_type)"
                        + " VALUES (?, ?, '2026-11-02T06:00:00Z'::timestamptz + (? || ' seconds')::interval,"
                        + " 1, 1, ?, ?)",
                reportId, asset, second, source, type);
    }

    private JsonNode track(String asset, String query) throws Exception {
        return mapper.readTree(
                mvc.perform(get("/api/v1/assets/" + asset + "/track" + query).header("Authorization", operator))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(n -> ids.add(n.get("reportId").asText()));
        return ids;
    }

    @Test
    void oldRowsWithoutAStoredTypeAreFilteredAndShownByTheTypeDerivedFromTheirSource() throws Exception {
        FeedClient feed = FeedClient.signIn(mvc, mapper);
        feed.send(line("srct-0", "SRCA", "2026-11-02T06:00:00Z", 1.0, 1.0)); // exists as an asset
        insertOld("srct-1", "SRCA", 1, "GND-4", null); // old row, no type
        insertOld("srct-2", "SRCA", 2, "MOB-1", "MOBILE"); // new row, type stored
        insertOld("srct-3", "SRCA", 3, "XYZ-9", null); // old row, unknown prefix
        insertOld("srct-4", "SRCA", 4, null, null); // old row, no source
        insertOld("srct-5", "SRCA", 5, "GND-2", "GROUND"); // new row

        assertThat(ids(track("SRCA", "?sourceType=GROUND").get("items"))).containsExactly("srct-1", "srct-5");
        assertThat(ids(track("SRCA", "?sourceType=MOBILE").get("items"))).containsExactly("srct-2");
        assertThat(ids(track("SRCA", "?sourceType=UNKNOWN").get("items"))).containsExactly("srct-3", "srct-4");
        assertThat(ids(track("SRCA", "?sourceType=SATELLITE").get("items"))).containsExactly("srct-0");
        assertThat(ids(track("SRCA", "").get("items"))).hasSize(6);

        // every point carries a type, stored or derived
        JsonNode all = track("SRCA", "").get("items");
        assertThat(all.get(1).get("sourceType").asText()).isEqualTo("GROUND");
        assertThat(all.get(4).get("sourceType").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void aBadSourceTypeIsRefused() throws Exception {
        FeedClient.signIn(mvc, mapper).send(line("srct-b", "SRCB", "2026-11-02T06:00:00Z", 1.0, 1.0));
        mvc.perform(get("/api/v1/assets/SRCB/track?sourceType=BOAT").header("Authorization", operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
