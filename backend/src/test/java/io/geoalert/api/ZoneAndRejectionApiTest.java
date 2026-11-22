package io.geoalert.api;

import static io.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.geoalert.support.FeedClient;
import io.geoalert.support.PostgresIntegrationTest;
import io.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The zones for the map, which assets are inside which zone, and the rejected lines. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ZoneAndRejectionApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    private FeedClient feed;
    private String operator;

    @BeforeEach
    void signIn() throws Exception {
        feed = FeedClient.signIn(mvc, mapper);
        operator = Tokens.bearer(Tokens.login(mvc, mapper, OPERATOR_USER));
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private ResultActions call(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", operator));
    }

    private JsonNode json(String url) throws Exception {
        return mapper.readTree(
                call(url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> texts(JsonNode items, String field) {
        return StreamSupport.stream(items.spliterator(), false).map(n -> n.get(field).asText()).toList();
    }

    private Map<String, Long> occupancy() throws Exception {
        Map<String, Long> counts = new HashMap<>();
        for (JsonNode item : json("/api/v1/zones/occupancy").get("items")) {
            counts.put(item.get("zoneId").asText(), item.get("assetCount").asLong());
        }
        return counts;
    }

    // ---- the zones

    @Test
    void theZonesAreListedWithTheirShapes() throws Exception {
        JsonNode items = json("/api/v1/zones").get("items");
        assertThat(items).hasSize(12);
        Map<String, JsonNode> byId = new HashMap<>();
        items.forEach(z -> byId.put(z.get("zoneId").asText(), z));

        assertThat(byId.get("Z-01").get("name").asText()).isEqualTo("Alpha Range");
        assertThat(byId.get("Z-01").get("zoneClass").asText()).isEqualTo("RESTRICTED");
        assertThat(byId.get("Z-09").get("zoneClass").asText()).isEqualTo("ADVISORY");
        assertThat(byId.get("Z-03").get("geometry").get("type").asText()).isEqualTo("Polygon");
        assertThat(byId.get("Z-03").get("geometry").get("coordinates")).hasSize(2); // outer ring and a hole
        assertThat(byId.get("Z-06").get("geometry").get("type").asText()).isEqualTo("MultiPolygon");
        assertThat(byId.get("Z-06").get("geometry").get("coordinates")).hasSize(2);

        // the zone across the 180 degree meridian is served with continuous longitudes
        JsonNode z08 = byId.get("Z-08");
        assertThat(z08.get("crossesAntimeridian").asBoolean()).isTrue();
        double maxLon = -Double.MAX_VALUE;
        for (JsonNode point : z08.get("geometry").get("coordinates").get(0)) {
            maxLon = Math.max(maxLon, point.get(0).asDouble());
        }
        assertThat(maxLon).isEqualTo(180.6);
        assertThat(byId.get("Z-12").get("crossesAntimeridian").asBoolean()).isFalse();
    }

    // ---- who is inside which zone

    @Test
    void theCountsFollowThePositionsAndCountEveryZoneAnAssetIsIn() throws Exception {
        Map<String, Long> before = occupancy();
        assertThat(before).hasSize(12); // every zone is listed, with 0 when empty

        // Z-02 (restricted), Z-10 (advisory), and the overlap of Z-04 and Z-05
        feed.send(
                line("zo-1", "ZOA", at(1), 35.4, 171.05),
                line("zo-2", "ZOB", at(1), 35.4, 171.05),
                line("zo-3", "ZOC", at(1), 36.55, 172.15),
                line("zo-4", "ZOD", at(1), 32.6, 174.8));
        Map<String, Long> after = occupancy();
        assertThat(after.get("Z-02") - before.get("Z-02")).isEqualTo(2);
        assertThat(after.get("Z-10") - before.get("Z-10")).isEqualTo(1);
        assertThat(after.get("Z-04") - before.get("Z-04")).isEqualTo(1);
        assertThat(after.get("Z-05") - before.get("Z-05")).isEqualTo(1);

        // one asset moves out: a newer report outside every zone
        feed.send(line("zo-5", "ZOA", at(2), 10.0, 20.0));
        // and a late report that is inside another zone changes nothing
        feed.send(line("zo-6", "ZOB", at(0), 38.2, 168.4));
        Map<String, Long> later = occupancy();
        assertThat(later.get("Z-02") - before.get("Z-02")).isEqualTo(1);
        assertThat(later.get("Z-01")).isEqualTo(after.get("Z-01"));
    }

    @Test
    void theAssetsInsideAZoneArePagedByAssetIdAndEachAppearsOnce() throws Exception {
        // Z-12: lon -178.5 to -177.6, lat 37.0 to 37.8 (across the sign change of longitude)
        for (int i = 0; i < 5; i++) {
            feed.send(line("zp-" + i, "ZP" + i, at(1), 37.4, -178.0));
        }
        feed.send(line("zp-out", "ZPOUT", at(1), 10.0, 20.0));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json("/api/v1/zones/Z-12/assets?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(texts(page.get("items"), "assetId"));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).containsExactly("ZP0", "ZP1", "ZP2", "ZP3", "ZP4");
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void theAssetsOfAZoneCarryTheirCurrentPosition() throws Exception {
        // Z-06 has two parts; this point is in the first one (lon 167.0-167.5, lat 33.0-33.5)
        feed.send(line("zq-1", "ZQ1", at(7), 33.25, 167.25));
        JsonNode items = json("/api/v1/zones/Z-06/assets?limit=500").get("items");
        JsonNode mine =
                StreamSupport.stream(items.spliterator(), false)
                        .filter(n -> n.get("assetId").asText().equals("ZQ1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(mine.get("reportId").asText()).isEqualTo("zq-1");
        assertThat(mine.get("lat").asDouble()).isEqualTo(33.25);
        assertThat(mine.get("observedAt").asText()).isEqualTo("2026-11-02T06:00:07.000Z");
    }

    @Test
    void anUnknownZoneABadLimitOrACursorIsRefused() throws Exception {
        call("/api/v1/zones/Z-99/assets")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (String query : List.of("limit=0", "limit=501", "limit=x", "cursor=%21%21", "cursor=bm9wZQ")) {
            call("/api/v1/zones/Z-12/assets?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void theFeedAccountCannotReadZonesOrRejections() throws Exception {
        String feedToken = Tokens.bearer(Tokens.login(mvc, mapper, FEED_USER));
        for (String url : List.of("/api/v1/zones", "/api/v1/zones/occupancy", "/api/v1/zones/Z-12/assets", "/api/v1/rejections")) {
            mvc.perform(get(url).header("Authorization", feedToken)).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    // ---- rejected lines

    @Test
    void rejectedLinesCanBeInspectedNewestFirstWithTheirReason() throws Exception {
        feed.send("{bad-r1", "{bad-r2", "{bad-r3");
        JsonNode items = json("/api/v1/rejections?limit=3").get("items");
        assertThat(texts(items, "rawLine")).containsExactly("{bad-r3", "{bad-r2", "{bad-r1");
        JsonNode newest = items.get(0);
        assertThat(newest.get("code").asText()).isEqualTo("MALFORMED_JSON");
        assertThat(newest.get("line").asInt()).isEqualTo(3);
        assertThat(newest.get("detail").asText()).isNotBlank();
        assertThat(newest.get("batchId").asText()).isNotBlank();
        assertThat(newest.get("rejectedAt").asText()).endsWith("Z");
    }

    @Test
    void theRejectedLinesArePagedWithACursorAndNothingRepeats() throws Exception {
        feed.send("{page-1", "{page-2", "{page-3", "{page-4", "{page-5");
        JsonNode first = json("/api/v1/rejections?limit=2");
        assertThat(texts(first.get("items"), "rawLine")).containsExactly("{page-5", "{page-4");
        String cursor = first.get("nextCursor").asText();
        JsonNode second = json("/api/v1/rejections?limit=2&cursor=" + cursor);
        assertThat(texts(second.get("items"), "rawLine")).containsExactly("{page-3", "{page-2");
        JsonNode third = json("/api/v1/rejections?limit=2&cursor=" + second.get("nextCursor").asText());
        assertThat(texts(third.get("items"), "rawLine")).startsWith("{page-1");
    }

    @Test
    void aBadLimitOrCursorOnTheRejectionListIsRefused() throws Exception {
        for (String query : List.of("limit=0", "limit=501", "cursor=%21%21", "cursor=bm9wZQ")) {
            call("/api/v1/rejections?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }
}
