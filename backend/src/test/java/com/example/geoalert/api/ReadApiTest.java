package com.example.geoalert.api;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.geoalert.support.FeedClient;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The read endpoints: stats, assets in a box, one asset, the track, alerts with paging, the audit. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ReadApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

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

    private static List<String> ids(JsonNode items, String field) {
        return StreamSupport.stream(items.spliterator(), false).map(n -> n.get(field).asText()).toList();
    }

    // ---- stats

    @Test
    void theStatsAddUpAndFollowEveryDelivery() throws Exception {
        JsonNode before =
                mapper.readTree(
                        mvc.perform(get("/api/v1/stats").header("Authorization", operator))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String a = line("st-a", "STA", at(1), 10.0, 20.0);
        String b = line("st-b", "STA", at(2), 10.1, 20.1);
        String c = line("st-c", "STB", at(3), 38.2, 168.4); // enters Z-01: one alert
        feed.send(a, b, c, "not json");
        feed.send(a); // delivered again: a duplicate

        JsonNode after =
                mapper.readTree(
                        mvc.perform(get("/api/v1/stats").header("Authorization", operator))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(after.get("received").asLong() - before.get("received").asLong()).isEqualTo(5);
        assertThat(after.get("accepted").asLong() - before.get("accepted").asLong()).isEqualTo(3);
        assertThat(after.get("duplicates").asLong() - before.get("duplicates").asLong()).isEqualTo(1);
        assertThat(after.get("rejected").asLong() - before.get("rejected").asLong()).isEqualTo(1);
        assertThat(after.get("assets").asLong() - before.get("assets").asLong()).isEqualTo(2);
        assertThat(after.get("alerts").get("open").asLong() - before.get("alerts").get("open").asLong())
                .isEqualTo(1);
        // received is always accepted + duplicates + rejected
        assertThat(after.get("received").asLong())
                .isEqualTo(
                        after.get("accepted").asLong()
                                + after.get("duplicates").asLong()
                                + after.get("rejected").asLong());
    }

    @Test
    void everyRoleCanReadTheStatsButNotAnonymousCallers() throws Exception {
        String feedToken = Tokens.bearer(Tokens.login(mvc, mapper, FEED_USER));
        mvc.perform(get("/api/v1/stats").header("Authorization", feedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.open").isNumber())
                .andExpect(jsonPath("$.alerts.claimed").isNumber())
                .andExpect(jsonPath("$.alerts.resolved").isNumber());
        mvc.perform(get("/api/v1/stats")).andExpect(status().isUnauthorized());
    }

    // ---- assets in a box

    @Test
    void assetsInABoxAreOrderedByIdAndTheRestAreLeftOut() throws Exception {
        feed.send(
                line("rd-a", "RDA", at(1), 61.0, -95.0),
                line("rd-b", "RDB", at(1), 62.0, -94.0),
                line("rd-c", "RDC", at(1), 63.0, -93.0));
        JsonNode result = json("/api/v1/assets?bbox=-96,60.5,-93.5,62.5");
        assertThat(ids(result.get("items"), "assetId")).containsExactly("RDA", "RDB");
        assertThat(result.get("truncated").asBoolean()).isFalse();
        JsonNode first = result.get("items").get(0);
        assertThat(first.get("reportId").asText()).isEqualTo("rd-a");
        assertThat(first.get("observedAt").asText()).isEqualTo("2026-11-02T06:00:01.000Z");
        assertThat(first.get("lat").asDouble()).isEqualTo(61.0);
        assertThat(first.get("speedKn").asDouble()).isEqualTo(10.5);
        assertThat(first.get("headingDeg").asDouble()).isEqualTo(90.0);
        assertThat(first.get("kind").asText()).isEqualTo("SURFACE");
        assertThat(first.get("source").asText()).isEqualTo("SAT-1");
    }

    @Test
    void theEdgesOfTheBoxAreInside() throws Exception {
        feed.send(line("rd-e", "RDEDGE", at(1), 64.0, -92.0));
        assertThat(ids(json("/api/v1/assets?bbox=-92,64,-91,65").get("items"), "assetId"))
                .contains("RDEDGE");
    }

    @Test
    void aBoxThatCrossesThe180MeridianFindsBothSides() throws Exception {
        feed.send(
                line("rd-d", "RDD", at(1), 61.0, 179.9),
                line("rd-e2", "RDE", at(1), 61.0, -179.9),
                line("rd-f", "RDF", at(1), 61.0, 170.0));
        List<String> found = ids(json("/api/v1/assets?bbox=179.5,60,-179.5,62").get("items"), "assetId");
        assertThat(found).contains("RDD", "RDE").doesNotContain("RDF");
    }

    @Test
    void theLimitBoundsTheAnswerAndSaysWhenItCutIt() throws Exception {
        feed.send(
                line("rd-l1", "RDL1", at(1), 66.0, -80.0),
                line("rd-l2", "RDL2", at(1), 66.1, -80.1),
                line("rd-l3", "RDL3", at(1), 66.2, -80.2));
        JsonNode cut = json("/api/v1/assets?bbox=-81,65.9,-79,66.3&limit=2");
        assertThat(ids(cut.get("items"), "assetId")).containsExactly("RDL1", "RDL2");
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        JsonNode exact = json("/api/v1/assets?bbox=-81,65.9,-79,66.3&limit=3");
        assertThat(exact.get("items")).hasSize(3);
        assertThat(exact.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void aBadBoxOrLimitIsRefused() throws Exception {
        for (String url :
                List.of(
                        "/api/v1/assets",
                        "/api/v1/assets?bbox=1,2,3",
                        "/api/v1/assets?bbox=a,b,c,d",
                        "/api/v1/assets?bbox=0,-91,10,10",
                        "/api/v1/assets?bbox=0,10,10,91",
                        "/api/v1/assets?bbox=0,20,10,10",
                        "/api/v1/assets?bbox=-181,0,10,10",
                        "/api/v1/assets?bbox=0,0,181,10",
                        "/api/v1/assets?bbox=0,0,10,10&limit=0",
                        "/api/v1/assets?bbox=0,0,10,10&limit=5001",
                        "/api/v1/assets?bbox=0,0,10,10&limit=abc")) {
            call(url).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void theMaximumLimitIsAccepted() throws Exception {
        call("/api/v1/assets?bbox=0,0,1,1&limit=5000").andExpect(status().isOk());
    }

    // ---- one asset and its track

    @Test
    void oneAssetShowsItsCurrentPosition() throws Exception {
        feed.send(line("rt-1", "RTA", at(5), 1.5, 2.5), line("rt-2", "RTA", at(9), 3.5, 4.5));
        JsonNode asset = json("/api/v1/assets/RTA");
        assertThat(asset.get("reportId").asText()).isEqualTo("rt-2");
        assertThat(asset.get("lat").asDouble()).isEqualTo(3.5);
    }

    @Test
    void anUnknownAssetIsNotFound() throws Exception {
        call("/api/v1/assets/NOSUCH1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call("/api/v1/assets/NOSUCH1/track")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theTrackIsOldestFirstAndKeepsLateReports() throws Exception {
        feed.send(
                line("tk-3", "RTB", at(30), 3.0, 3.0),
                line("tk-1", "RTB", at(10), 1.0, 1.0),
                line("tk-5", "RTB", at(50), 5.0, 5.0));
        feed.send(line("tk-2", "RTB", at(20), 2.0, 2.0)); // late: older than the position
        JsonNode track = json("/api/v1/assets/RTB/track");
        assertThat(ids(track.get("items"), "reportId")).containsExactly("tk-1", "tk-2", "tk-3", "tk-5");
        assertThat(track.get("truncated").asBoolean()).isFalse();
        JsonNode point = track.get("items").get(0);
        assertThat(point.get("observedAt").asText()).isEqualTo("2026-11-02T06:00:10.000Z");
        assertThat(point.get("source").asText()).isEqualTo("SAT-1");
        // the current position is still the newest report
        assertThat(json("/api/v1/assets/RTB").get("reportId").asText()).isEqualTo("tk-5");
    }

    @Test
    void theTrackCanBeCutByTimeAndByLimit() throws Exception {
        feed.send(
                line("tf-1", "RTC", at(10), 1.0, 1.0),
                line("tf-2", "RTC", at(20), 2.0, 2.0),
                line("tf-3", "RTC", at(30), 3.0, 3.0),
                line("tf-4", "RTC", at(40), 4.0, 4.0));
        assertThat(ids(json("/api/v1/assets/RTC/track?from=" + at(20) + "&to=" + at(30)).get("items"), "reportId"))
                .containsExactly("tf-2", "tf-3");
        assertThat(ids(json("/api/v1/assets/RTC/track?from=" + at(25)).get("items"), "reportId"))
                .containsExactly("tf-3", "tf-4");
        JsonNode cut = json("/api/v1/assets/RTC/track?limit=3");
        assertThat(ids(cut.get("items"), "reportId")).containsExactly("tf-1", "tf-2", "tf-3");
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        assertThat(json("/api/v1/assets/RTC/track?from=" + at(100)).get("items")).isEmpty();
    }

    @Test
    void aBadTrackParameterIsRefused() throws Exception {
        feed.send(line("tb-1", "RTD", at(1), 1.0, 1.0));
        for (String query : List.of("from=yesterday", "to=2026-11-02", "limit=0", "limit=5001")) {
            call("/api/v1/assets/RTD/track?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        call("/api/v1/assets/RTD/track?limit=5000").andExpect(status().isOk());
    }

    @Test
    void aTrackTimeWithAnOffsetIsUnderstood() throws Exception {
        feed.send(line("to-1", "RTE", at(10), 1.0, 1.0), line("to-2", "RTE", at(20), 2.0, 2.0));
        // 06:00:15 UTC written as 10:00:15+04:00
        String body =
                mvc.perform(
                                get("/api/v1/assets/RTE/track")
                                        .queryParam("from", "2026-11-02T10:00:15+04:00")
                                        .header("Authorization", operator))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ids(mapper.readTree(body).get("items"), "reportId")).containsExactly("to-2");
    }

    // ---- alerts

    private long alert(String asset, String zone, String status) {
        return jdbc.queryForObject(
                "INSERT INTO alerts (asset_id, zone_id, status, trigger_report_id,"
                        + " trigger_observed_at) VALUES (?, ?, ?, 'r-read', now()) RETURNING id",
                Long.class,
                asset,
                zone,
                status);
    }

    @Test
    void followingTheCursorsReturnsEveryAlertExactlyOnceOldestFirst() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(alert("PGA" + i, "ZPAGE", "OPEN"));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json("/api/v1/alerts?zoneId=ZPAGE&limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(ids(page.get("items"), "alertId"));
            assertThat(page.get("items").size()).isLessThanOrEqualTo(3);
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3); // 3 + 3 + 1
        assertThat(seen).containsExactlyElementsOf(created.stream().map(String::valueOf).toList());
    }

    @Test
    void theLastPageHasNoCursorEvenWhenItIsExactlyFull() throws Exception {
        for (int i = 0; i < 3; i++) {
            alert("PGB" + i, "ZFULL", "OPEN");
        }
        JsonNode page = json("/api/v1/alerts?zoneId=ZFULL&limit=3");
        assertThat(page.get("items")).hasSize(3);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void theListCanBeFilteredByStatusAssetAndZone() throws Exception {
        alert("FLT1", "ZFILT", "OPEN");
        alert("FLT2", "ZFILT", "CLAIMED");
        alert("FLT3", "ZFILT", "RESOLVED");
        alert("FLT1", "ZFILT2", "OPEN");
        assertThat(ids(json("/api/v1/alerts?zoneId=ZFILT&status=CLAIMED").get("items"), "assetId"))
                .containsExactly("FLT2");
        assertThat(ids(json("/api/v1/alerts?zoneId=ZFILT").get("items"), "assetId"))
                .containsExactly("FLT1", "FLT2", "FLT3");
        assertThat(ids(json("/api/v1/alerts?assetId=FLT1").get("items"), "zoneId"))
                .containsExactly("ZFILT", "ZFILT2");
        assertThat(json("/api/v1/alerts?zoneId=ZFILT&assetId=FLT2&status=OPEN").get("items")).isEmpty();
    }

    @Test
    void aBadFilterLimitOrCursorIsRefused() throws Exception {
        for (String query :
                List.of(
                        "status=DONE",
                        "limit=0",
                        "limit=501",
                        "limit=x",
                        "cursor=%21%21%21",
                        "cursor=bm90LWEtY3Vyc29y")) {
            call("/api/v1/alerts?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        call("/api/v1/alerts?limit=500").andExpect(status().isOk());
    }

    @Test
    void oneAlertAndItsAuditTrailCanBeRead() throws Exception {
        feed.send(line("al-1", "ALR", at(1), 10.0, 20.0), line("al-2", "ALR", at(2), 38.2, 168.4));
        long id = jdbc.queryForObject("SELECT id FROM alerts WHERE asset_id = 'ALR'", Long.class);
        mvc.perform(post("/api/v1/alerts/{id}/claim", id).header("Authorization", operator));

        JsonNode one = json("/api/v1/alerts/" + id);
        assertThat(one.get("alertId").asText()).isEqualTo(Long.toString(id));
        assertThat(one.get("zoneId").asText()).isEqualTo("Z-01");
        assertThat(one.get("status").asText()).isEqualTo("CLAIMED");
        assertThat(one.get("claimedBy").asText()).isEqualTo(OPERATOR_USER);
        assertThat(one.get("triggerReportId").asText()).isEqualTo("al-2");
        assertThat(one.get("triggerObservedAt").asText()).isEqualTo("2026-11-02T06:00:02.000Z");
        assertThat(one.get("resolvedBy").isNull()).isTrue();

        JsonNode audit = json("/api/v1/alerts/" + id + "/audit");
        assertThat(ids(audit.get("items"), "action")).containsExactly("OPENED", "CLAIMED");
        assertThat(ids(audit.get("items"), "actor")).containsExactly("system", OPERATOR_USER);
        assertThat(audit.get("items").get(0).get("fromStatus").isNull()).isTrue();
        assertThat(audit.get("items").get(1).get("fromStatus").asText()).isEqualTo("OPEN");
        assertThat(audit.get("items").get(1).get("toStatus").asText()).isEqualTo("CLAIMED");
    }

    @Test
    void anUnknownAlertIsNotFoundForTheAlertAndItsAudit() throws Exception {
        for (String url :
                List.of(
                        "/api/v1/alerts/999999999",
                        "/api/v1/alerts/999999999/audit",
                        "/api/v1/alerts/abc",
                        "/api/v1/alerts/abc/audit")) {
            call(url).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void theFeedAccountCannotReadAssetsOrAlerts() throws Exception {
        String feedToken = Tokens.bearer(Tokens.login(mvc, mapper, FEED_USER));
        for (String url : List.of("/api/v1/assets?bbox=0,0,1,1", "/api/v1/assets/RTA", "/api/v1/alerts")) {
            mvc.perform(get(url).header("Authorization", feedToken)).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }
}
