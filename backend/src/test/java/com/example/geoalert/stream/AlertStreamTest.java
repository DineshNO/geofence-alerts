package com.example.geoalert.stream;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.geoalert.GeoAlertApplication;
import com.example.geoalert.stream.domain.AlertEvent;
import com.example.geoalert.stream.domain.AlertEventBus;
import com.example.geoalert.support.HttpApi;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.SseClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The live alert stream. Two backend instances run against one database, as behind the load
 * balancer: a change made through one instance reaches the streams of both, through NOTIFY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertStreamTest extends PostgresIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(8);

    private static ConfigurableApplicationContext second;

    @LocalServerPort private int port;
    @Autowired private AlertEventBus bus;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private SecretKey jwtSigningKey;

    private HttpApi a; // the instance of this test context
    private HttpApi b; // the second instance
    private static int nextAsset = 0;

    @BeforeAll
    static void startSecondInstance() {
        second =
                new SpringApplicationBuilder(GeoAlertApplication.class)
                        .run(instanceArgs("backend-b"));
    }

    @AfterAll
    static void stopSecondInstance() {
        if (second != null) {
            second.close();
        }
    }

    @BeforeEach
    void connect() {
        a = new HttpApi("http://localhost:" + port);
        b = new HttpApi("http://localhost:" + second.getEnvironment().getProperty("local.server.port"));
    }

    private static synchronized String newAsset() {
        return "STR" + (nextAsset++);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    /** Two reports, the second enters Z-01: the system opens one alert. */
    private void enterZone(HttpApi api, String feedBearer, String asset) throws Exception {
        api.deliver(
                feedBearer,
                line("s-" + asset + "-a", asset, at(1), 10.0, 20.0),
                line("s-" + asset + "-b", asset, at(2), 38.2, 168.4));
    }

    private JsonNode data(SseClient.Event event) throws Exception {
        return mapper.readTree(event.data());
    }

    // ---- who may open the stream

    @Test
    void theStreamNeedsASignedInOperatorOrSupervisor() throws Exception {
        assertThat(a.get("/api/v1/alerts/stream", null).statusCode()).isEqualTo(401);
        assertThat(a.get("/api/v1/alerts/stream", a.bearer("feed")).statusCode()).isEqualTo(403);
        assertThat(a.get("/api/v1/alerts/stream", "Bearer not-a-token").statusCode()).isEqualTo(401);
    }

    @Test
    void theStreamStartsWithAReadyEvent() throws Exception {
        try (SseClient stream = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"))) {
            assertThat(stream.status()).isEqualTo(200);
            SseClient.Event first = stream.next(WAIT);
            assertThat(first).isNotNull();
            assertThat(first.name()).isEqualTo("ready");
        }
    }

    // ---- events

    @Test
    void anAlertOpenedByAnIngestBatchReachesTheStreamOfTheSameInstance() throws Exception {
        String feed = a.bearer("feed");
        String asset = newAsset();
        try (SseClient stream = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"))) {
            enterZone(a, feed, asset);
            SseClient.Event event = stream.awaitEvent("alert.changed", WAIT);
            assertThat(event).isNotNull();
            JsonNode json = data(event);
            assertThat(json.get("type").asText()).isEqualTo("OPENED");
            assertThat(json.get("status").asText()).isEqualTo("OPEN");
            assertThat(json.get("assetId").asText()).isEqualTo(asset);
            assertThat(json.get("zoneId").asText()).isEqualTo("Z-01");
            long id = jdbc.queryForObject("SELECT id FROM alerts WHERE asset_id = ?", Long.class, asset);
            assertThat(json.get("alertId").asText()).isEqualTo(Long.toString(id));
        }
    }

    @Test
    void aChangeMadeThroughOneInstanceReachesTheStreamsOfBoth() throws Exception {
        String feed = a.bearer("feed");
        String asset = newAsset();
        try (SseClient onA = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"));
                SseClient onB = SseClient.open(b.url("/api/v1/alerts/stream"), b.bearer("op02"))) {
            // the alert is opened by a batch delivered to instance A
            enterZone(a, feed, asset);
            assertThat(onA.awaitEvent("alert.changed", WAIT)).isNotNull();
            SseClient.Event opened = onB.awaitEvent("alert.changed", WAIT);
            assertThat(opened).isNotNull();
            String alertId = data(opened).get("alertId").asText();

            // a claim made on instance B reaches the stream of instance A
            assertThat(b.command(b.bearer("op02"), alertId, "claim").statusCode()).isEqualTo(200);
            SseClient.Event claimedOnA = onA.awaitEvent("alert.changed", WAIT);
            assertThat(claimedOnA).isNotNull();
            assertThat(data(claimedOnA).get("type").asText()).isEqualTo("CLAIMED");
            assertThat(data(claimedOnA).get("status").asText()).isEqualTo("CLAIMED");
            assertThat(onB.awaitEvent("alert.changed", WAIT)).isNotNull();

            // and a resolve made on instance A reaches the stream of instance B
            assertThat(a.command(a.bearer("sup01"), alertId, "release").statusCode()).isEqualTo(200);
            SseClient.Event released = onB.awaitEvent("alert.changed", WAIT);
            assertThat(released).isNotNull();
            assertThat(data(released).get("type").asText()).isEqualTo("RELEASED");
        }
    }

    @Test
    void anAlertOpenedThroughInstanceBReachesTheStreamOfInstanceA() throws Exception {
        String asset = newAsset();
        try (SseClient onA = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"))) {
            enterZone(b, b.bearer("feed"), asset);
            SseClient.Event event = onA.awaitEvent("alert.changed", WAIT);
            assertThat(event).isNotNull();
            assertThat(data(event).get("assetId").asText()).isEqualTo(asset);
        }
    }

    @Test
    void nothingIsSentForAChangeThatIsRolledBack() throws Exception {
        AlertEvent event = new AlertEvent("CLAIMED", "1", "CLAIMED", "ROLLBACK1", "Z-01");
        try (SseClient onB = SseClient.open(b.url("/api/v1/alerts/stream"), b.bearer("op01"))) {
            tx.execute(
                    status -> {
                        bus.publish(List.of(event));
                        status.setRollbackOnly();
                        return null;
                    });
            assertThat(onB.awaitEvent("alert.changed", Duration.ofMillis(1500))).isNull();

            // the same event in a transaction that commits is delivered
            tx.executeWithoutResult(status -> bus.publish(List.of(event)));
            SseClient.Event delivered = onB.awaitEvent("alert.changed", WAIT);
            assertThat(delivered).isNotNull();
            assertThat(data(delivered).get("assetId").asText()).isEqualTo("ROLLBACK1");
        }
    }

    // ---- keeping the stream alive and ending it

    @Test
    void anIdleStreamIsPingedSoProxiesKeepItOpen() throws Exception {
        try (SseClient stream = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"))) {
            Thread.sleep(1200);
            assertThat(stream.comments()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void theStreamEndsWhenTheTokenExpires() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("geofence-alerts")
                        .subject("op01")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(2))
                        .claim("role", "OPERATOR")
                        .build();
        String shortToken =
                new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey))
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        try (SseClient stream = SseClient.open(a.url("/api/v1/alerts/stream"), "Bearer " + shortToken)) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.awaitEnd(Duration.ofSeconds(10))).isTrue();
        }
        // and the expired token is refused when the console tries to reconnect (the token check
        // tolerates one second of clock difference)
        Thread.sleep(1600);
        assertThat(a.get("/api/v1/alerts/stream", "Bearer " + shortToken).statusCode()).isEqualTo(401);
    }

    // ---- when events may have been missed

    @Test
    void clientsAreToldToRefetchWhenTheListenerLostItsConnectionAndCameBack() throws Exception {
        String feed = a.bearer("feed");
        try (SseClient stream = SseClient.open(a.url("/api/v1/alerts/stream"), a.bearer("op01"))) {
            assertThat(stream.next(WAIT).name()).isEqualTo("ready");

            // the database drops every listener connection (a restart or a network cut)
            jdbc.queryForList(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                            + " WHERE application_name = 'geoalert-listener'");

            SseClient.Event resync = stream.awaitEvent("resync", Duration.ofSeconds(20));
            assertThat(resync).isNotNull();

            // after reconnecting, events flow again
            String asset = newAsset();
            enterZone(a, feed, asset);
            SseClient.Event event = stream.awaitEvent("alert.changed", WAIT);
            assertThat(event).isNotNull();
            assertThat(data(event).get("assetId").asText()).isEqualTo(asset);
        }
    }
}
