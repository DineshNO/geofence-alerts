package com.example.geoalert.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;

/** Test helper: delivers batches as the feed account does and builds report lines. */
public final class FeedClient {

    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final String token;

    public FeedClient(MockMvc mvc, ObjectMapper mapper, String token) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.token = token;
    }

    public static FeedClient signIn(MockMvc mvc, ObjectMapper mapper) throws Exception {
        return new FeedClient(
                mvc, mapper, Tokens.login(mvc, mapper, PostgresIntegrationTest.FEED_USER));
    }

    /** One report line; the id and asset id are chosen by the test. */
    public static String line(
            String reportId, String assetId, String observedAt, double lat, double lon) {
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

    /** Delivers the lines as one batch and returns the answer. */
    public JsonNode send(List<String> lines) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/reports/batch")
                                        .header("Authorization", Tokens.bearer(token))
                                        .contentType("application/x-ndjson")
                                        .content(String.join("\n", lines)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(body);
    }

    public JsonNode send(String... lines) throws Exception {
        return send(List.of(lines));
    }
}
