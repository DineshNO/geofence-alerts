package com.example.geoalert.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The batch endpoint, and R1: delivering a report again changes nothing. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class IngestApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private String feedToken;

    @BeforeEach
    void signIn() throws Exception {
        feedToken = Tokens.login(mvc, mapper, FEED_USER);
    }

    static String line(String reportId, String assetId, String observedAt, double lat, double lon) {
        return "{\"reportId\":\""
                + reportId
                + "\",\"assetId\":\""
                + assetId
                + "\",\"kind\":\"SURFACE\",\"observedAt\":\""
                + observedAt
                + "\",\"lat\":"
                + lat
                + ",\"lon\":"
                + lon
                + ",\"speedKn\":10.5,\"headingDeg\":90,\"source\":\"SAT-1\"}";
    }

    /** A report with a distinct id and time, far from every zone. */
    static String simple(String prefix, int n) {
        // asset ids must match ^[A-Z0-9][A-Z0-9-]{2,31}$
        String asset = "AST-" + prefix.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return line(prefix + n, asset, "2026-11-02T06:00:%02d.000Z".formatted(n % 60), 10.0, 20.0);
    }

    private JsonNode send(String requestId, List<String> lines) throws Exception {
        var request =
                post("/api/v1/reports/batch")
                        .header("Authorization", Tokens.bearer(feedToken))
                        .contentType("application/x-ndjson")
                        .content(String.join("\n", lines));
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        String body =
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode send(List<String> lines) throws Exception {
        return send(null, lines);
    }

    private long storedReports(String idPrefix) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE report_id LIKE ?", Long.class, idPrefix + "%");
    }

    @Test
    void newReportsAreAcceptedAndStored() throws Exception {
        List<String> lines = IntStream.range(0, 3).mapToObj(n -> simple("new1-", n)).toList();
        JsonNode result = send(lines);
        assertThat(result.get("received").asInt()).isEqualTo(3);
        assertThat(result.get("accepted").asInt()).isEqualTo(3);
        assertThat(result.get("duplicates").asInt()).isZero();
        assertThat(result.get("rejected").asInt()).isZero();
        assertThat(result.get("rejections")).isEmpty();
        assertThat(storedReports("new1-")).isEqualTo(3);
    }

    @Test
    void deliveringTheSameBatchAgainChangesNothing() throws Exception {
        List<String> lines = IntStream.range(0, 5).mapToObj(n -> simple("again-", n)).toList();
        send(lines);
        JsonNode second = send(lines);
        assertThat(second.get("accepted").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(5);
        assertThat(storedReports("again-")).isEqualTo(5);
        // and a third and fourth time, as a sender that keeps retrying would
        send(lines);
        send(lines);
        assertThat(storedReports("again-")).isEqualTo(5);
    }

    @Test
    void aReportIdRepeatedInsideOneBatchIsStoredOnce() throws Exception {
        String first = simple("dup-", 1);
        JsonNode result = send(List.of(first, simple("dup-", 2), first));
        assertThat(result.get("accepted").asInt()).isEqualTo(2);
        assertThat(result.get("duplicates").asInt()).isEqualTo(1);
        assertThat(storedReports("dup-")).isEqualTo(2);
    }

    @Test
    void theSameBatchDeliveredInParallelIsStoredOnce() throws Exception {
        List<String> lines = IntStream.range(0, 50).mapToObj(n -> simple("par1-", n)).toList();
        int senders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        try {
            List<Callable<JsonNode>> tasks = new ArrayList<>();
            for (int i = 0; i < senders; i++) {
                tasks.add(() -> send(lines));
            }
            int accepted = 0;
            int duplicates = 0;
            for (Future<JsonNode> f : pool.invokeAll(tasks)) {
                JsonNode result = f.get();
                accepted += result.get("accepted").asInt();
                duplicates += result.get("duplicates").asInt();
            }
            assertThat(accepted).isEqualTo(50);
            assertThat(duplicates).isEqualTo(50 * (senders - 1));
            assertThat(storedReports("par1-")).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void overlappingBatchesDeliveredInParallelStoreEveryReportOnce() throws Exception {
        List<String> a = IntStream.range(0, 60).mapToObj(n -> simple("par2-", n)).toList();
        List<String> b = IntStream.range(30, 90).mapToObj(n -> simple("par2-", n)).toList();
        List<String> c = IntStream.range(60, 120).mapToObj(n -> simple("par2-", n)).toList();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<JsonNode>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> send(a));
                tasks.add(() -> send(b));
                tasks.add(() -> send(c));
            }
            int accepted = 0;
            for (Future<JsonNode> f : pool.invokeAll(tasks)) {
                accepted += f.get().get("accepted").asInt();
            }
            assertThat(accepted).isEqualTo(120);
            assertThat(storedReports("par2-")).isEqualTo(120);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void badLinesDoNotBlockGoodLinesAndEmptyLinesAreNotNumbered() throws Exception {
        List<String> lines =
                List.of(
                        simple("bad1-", 1),
                        "",
                        "{this is not json",
                        simple("bad1-", 2),
                        "   ",
                        "{\"reportId\":\"bad1-x\",\"assetId\":\"AST-1\",\"lat\":1,\"lon\":2}");
        JsonNode result = send(lines);
        // 4 non-empty lines: two good, one malformed (line 2), one missing a field (line 4)
        assertThat(result.get("received").asInt()).isEqualTo(4);
        assertThat(result.get("accepted").asInt()).isEqualTo(2);
        assertThat(result.get("rejected").asInt()).isEqualTo(2);
        JsonNode rejections = result.get("rejections");
        assertThat(rejections.get(0).get("line").asInt()).isEqualTo(2);
        assertThat(rejections.get(0).get("code").asText()).isEqualTo("MALFORMED_JSON");
        assertThat(rejections.get(1).get("line").asInt()).isEqualTo(4);
        assertThat(rejections.get(1).get("code").asText()).isEqualTo("MISSING_FIELD");
        assertThat(storedReports("bad1-")).isEqualTo(2);
    }

    @Test
    void everyRejectedLineIsKeptWithItsReasonAndTime() throws Exception {
        String bad = "{\"reportId\":\"keep-1\",\"assetId\":\"AST-1\",\"lat\":999,\"lon\":2,"
                + "\"observedAt\":\"2026-11-02T06:00:00Z\"}";
        send(List.of(bad));
        var row =
                jdbc.queryForMap(
                        "SELECT code, detail, raw_line, line_no, created_at"
                                + " FROM rejected_lines WHERE raw_line = ?",
                        bad);
        assertThat(row.get("code")).isEqualTo("INVALID_COORDINATE");
        assertThat((String) row.get("detail")).contains("lat");
        assertThat(row.get("line_no")).isEqualTo(1);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void aHugeRejectedLineIsStoredTruncated() throws Exception {
        String huge = "{\"junk\":\"" + "x".repeat(10_000);
        send(List.of(huge));
        Integer length =
                jdbc.queryForObject(
                        "SELECT max(length(raw_line)) FROM rejected_lines WHERE raw_line LIKE ?",
                        Integer.class,
                        "{\"junk\":\"xxx%");
        assertThat(length).isEqualTo(2048);
    }

    @Test
    void aLineWithANulCharacterIsRejectedWithoutBreakingTheBatch() throws Exception {
        String withNul = simple("nul-", 1).replace("SAT-1", "SAT\\u0000-1") + "\u0000";
        JsonNode result = send(List.of(withNul, simple("nul-", 2)));
        assertThat(result.get("accepted").asInt()).isEqualTo(1);
        assertThat(result.get("rejected").asInt()).isEqualTo(1);
    }

    @Test
    void anInvalidOptionalValueIsStoredAsNullAndTheReportIsKept() throws Exception {
        String line =
                simple("opt-", 1).replace("\"speedKn\":10.5", "\"speedKn\":\"fast\"")
                        .replace("\"headingDeg\":90", "\"headingDeg\":\"14.5\"");
        JsonNode result = send(List.of(line));
        assertThat(result.get("accepted").asInt()).isEqualTo(1);
        var row =
                jdbc.queryForMap(
                        "SELECT speed_kn, heading_deg FROM reports WHERE report_id = 'opt-1'");
        assertThat(row.get("speed_kn")).isNull();
        assertThat(row.get("heading_deg")).isEqualTo(14.5);
    }

    @Test
    void everyBatchLeavesARowInTheBatchLog() throws Exception {
        send(
                "batch-log-req-1",
                List.of(simple("log-", 1), simple("log-", 2), "not json", simple("log-", 1)));
        var row =
                jdbc.queryForMap(
                        "SELECT received, accepted, duplicates, rejected, instance"
                                + " FROM ingest_batches WHERE request_id = 'batch-log-req-1'");
        assertThat(row.get("received")).isEqualTo(4);
        assertThat(row.get("accepted")).isEqualTo(2);
        assertThat(row.get("duplicates")).isEqualTo(1);
        assertThat(row.get("rejected")).isEqualTo(1);
        assertThat(row.get("instance")).isEqualTo("backend");
    }

    @Test
    void receivedAlwaysEqualsAcceptedPlusDuplicatesPlusRejected() throws Exception {
        for (int i = 0; i < 5; i++) {
            send(
                    List.of(
                            simple("inv-", i),
                            simple("inv-", i),
                            "oops",
                            simple("inv-", i + 100)));
        }
        Integer broken =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ingest_batches"
                                + " WHERE received <> accepted + duplicates + rejected",
                        Integer.class);
        assertThat(broken).isZero();
    }

    @Test
    void aWrongContentTypeIsRefused() throws Exception {
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(feedToken))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void moreThanAThousandLinesIsRefused() throws Exception {
        List<String> lines = IntStream.range(0, 1001).mapToObj(n -> simple("big-", n)).toList();
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(feedToken))
                                .contentType("application/x-ndjson")
                                .content(String.join("\n", lines)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(storedReports("big-")).isZero();
    }

    @Test
    void exactlyAThousandLinesIsAccepted() throws Exception {
        List<String> lines = IntStream.range(0, 1000).mapToObj(n -> simple("edge-", n)).toList();
        JsonNode result = send(lines);
        assertThat(result.get("accepted").asInt()).isEqualTo(1000);
    }

    @Test
    void aBodyOverFiveMegabytesIsRefused() throws Exception {
        String big = "x".repeat(5 * 1024 * 1024 + 1);
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(feedToken))
                                .contentType("application/x-ndjson")
                                .content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void onlyTheFeedRoleMayDeliver() throws Exception {
        String operator = Tokens.login(mvc, mapper, OPERATOR_USER);
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(operator))
                                .contentType("application/x-ndjson")
                                .content(simple("role-", 1)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .contentType("application/x-ndjson")
                                .content(simple("role-", 1)))
                .andExpect(status().isUnauthorized());
        assertThat(storedReports("role-")).isZero();
    }
}
