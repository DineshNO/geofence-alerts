package com.example.geoalert.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Test helper: calls a running instance over real HTTP (login, deliver a batch, a command). */
public final class HttpApi {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String base;

    public HttpApi(String base) {
        this.base = base;
    }

    public String bearer(String username) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("username", username, "password", PostgresIntegrationTest.TEST_PASSWORD));
        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build());
        return "Bearer " + MAPPER.readTree(response.body()).get("accessToken").asText();
    }

    public HttpResponse<String> deliver(String bearer, String... lines) throws Exception {
        return send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/reports/batch"))
                        .header("Authorization", bearer)
                        .header("Content-Type", "application/x-ndjson")
                        .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", lines)))
                        .build());
    }

    public HttpResponse<String> command(String bearer, String alertId, String action) throws Exception {
        return send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/alerts/" + alertId + "/" + action))
                        .header("Authorization", bearer)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());
    }

    public HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return send(request.build());
    }

    public JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    public String url(String path) {
        return base + path;
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
