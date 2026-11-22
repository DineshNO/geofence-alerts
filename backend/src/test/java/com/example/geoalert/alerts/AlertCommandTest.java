package com.example.geoalert.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** claim, release and resolve through the API, and R4: exactly one owner under parallel claims. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AlertCommandTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static int nextAsset = 0;

    private synchronized long newAlert(String status) {
        String asset = "CMD" + (nextAsset++);
        return jdbc.queryForObject(
                "INSERT INTO alerts (asset_id, zone_id, status, trigger_report_id,"
                        + " trigger_observed_at) VALUES (?, 'Z-01', ?, 'r-cmd', now()) RETURNING id",
                Long.class,
                asset,
                status);
    }

    private String token(String user) throws Exception {
        return Tokens.bearer(Tokens.login(mvc, mapper, user));
    }

    private ResultActions claim(long id, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/alerts/{id}/claim", id).header("Authorization", bearer));
    }

    private ResultActions release(long id, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/alerts/{id}/release", id).header("Authorization", bearer));
    }

    private ResultActions resolve(long id, String bearer, String body) throws Exception {
        return mvc.perform(
                post("/api/v1/alerts/{id}/resolve", id)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content(body));
    }

    private static String note(String text) {
        return "{\"note\":\"" + text + "\"}";
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM alerts WHERE id = ?", id);
    }

    // ---- claim

    @Test
    void claimingAnOpenAlertMakesTheCallerTheOwner() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(Long.toString(id)))
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.claimedBy").value("op01"))
                .andExpect(jsonPath("$.claimedAt").isNotEmpty());
        assertThat(row(id)).containsEntry("status", "CLAIMED").containsEntry("claimed_by", "op01");
    }

    @Test
    void claimingAgainAsTheOwnerIsOkAndChangesNothing() throws Exception {
        long id = newAlert("OPEN");
        String bearer = token("op01");
        claim(id, bearer).andExpect(status().isOk());
        Object claimedAt = row(id).get("claimed_at");
        claim(id, bearer).andExpect(status().isOk()).andExpect(jsonPath("$.claimedBy").value("op01"));
        assertThat(row(id).get("claimed_at")).isEqualTo(claimedAt);
    }

    @Test
    void claimingAnAlertAnotherUserOwnsIsAConflict() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01")).andExpect(status().isOk());
        claim(id, token("op02"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ALERT_ALREADY_CLAIMED"))
                .andExpect(jsonPath("$.detail").value("The alert is already claimed by op01."));
        assertThat(row(id)).containsEntry("claimed_by", "op01");
    }

    @Test
    void claimingAResolvedAlertIsAnInvalidState() throws Exception {
        long id = newAlert("RESOLVED");
        claim(id, token("op01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ---- release

    @Test
    void theOwnerCanReleaseAndTheAlertHasNoOwnerAfterwards() throws Exception {
        long id = newAlert("OPEN");
        String bearer = token("op01");
        claim(id, bearer);
        release(id, bearer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.claimedBy").doesNotExist());
        assertThat(row(id)).containsEntry("status", "OPEN").containsEntry("claimed_by", null);
        // and somebody else can now claim it
        claim(id, token("op02")).andExpect(status().isOk());
    }

    @Test
    void anotherOperatorCannotRelease() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01"));
        release(id, token("op02"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ALERT_OWNER"));
        assertThat(row(id)).containsEntry("status", "CLAIMED");
    }

    @Test
    void aSupervisorCanReleaseSomebodyElsesAlert() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01"));
        release(id, token("sup01")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void releasingAnAlertThatIsNotClaimedIsAnInvalidState() throws Exception {
        long id = newAlert("OPEN");
        release(id, token("op01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ---- resolve

    @Test
    void theOwnerResolvesWithANote() throws Exception {
        long id = newAlert("OPEN");
        String bearer = token("op01");
        claim(id, bearer);
        resolve(id, bearer, note("Vessel left the zone, checked on radar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value("op01"))
                .andExpect(jsonPath("$.resolutionNote").value("Vessel left the zone, checked on radar"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    @Test
    void resolvingAgainBySameUserIsOkAndKeepsTheFirstNote() throws Exception {
        long id = newAlert("OPEN");
        String bearer = token("op01");
        claim(id, bearer);
        resolve(id, bearer, note("first note")).andExpect(status().isOk());
        resolve(id, bearer, note("second note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionNote").value("first note"));
        assertThat(row(id)).containsEntry("resolution_note", "first note");
    }

    @Test
    void resolvingAnAlertResolvedBySomebodyElseIsAnInvalidState() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01"));
        resolve(id, token("op01"), note("done")).andExpect(status().isOk());
        resolve(id, token("op02"), note("also done"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void anotherOperatorCannotResolveButASupervisorCan() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("op01"));
        resolve(id, token("op02"), note("not mine"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ALERT_OWNER"));
        resolve(id, token("sup01"), note("closed by the supervisor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("sup01"))
                .andExpect(jsonPath("$.claimedBy").value("op01"));
    }

    @Test
    void anOpenAlertCannotBeResolvedBeforeItIsClaimed() throws Exception {
        long id = newAlert("OPEN");
        resolve(id, token("op01"), note("too early"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void theNoteMustBePresentNotBlankAndAtMostFiveHundredCharacters() throws Exception {
        long id = newAlert("OPEN");
        String bearer = token("op01");
        claim(id, bearer);
        for (String body : List.of("{}", "{\"note\":null}", note(""), note("   "), note("x".repeat(501)))) {
            resolve(id, bearer, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(row(id)).containsEntry("status", "CLAIMED");
        resolve(id, bearer, note("x".repeat(500))).andExpect(status().isOk());
    }

    // ---- not found, roles

    @Test
    void anUnknownOrMalformedAlertIdIsNotFound() throws Exception {
        String bearer = token("op01");
        claim(999_999_999L, bearer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/alerts/not-a-number/claim").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theFeedAccountAndAnonymousCallersCannotUseTheCommands() throws Exception {
        long id = newAlert("OPEN");
        claim(id, token("feed")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts/{id}/claim", id)).andExpect(status().isUnauthorized());
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    // ---- R4 under parallel requests

    @Test
    void twentyOperatorsClaimingTheSameAlertAtOnceExactlyOneWins() throws Exception {
        long id = newAlert("OPEN");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            tokens.add(token("op%02d".formatted(i)));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<String> winners = new ArrayList<>();
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (String bearer : tokens) {
                tasks.add(
                        () -> {
                            start.await();
                            var response = claim(id, bearer).andReturn().getResponse();
                            if (response.getStatus() == 200) {
                                ok.incrementAndGet();
                                JsonNode body = mapper.readTree(response.getContentAsString());
                                synchronized (winners) {
                                    winners.add(body.get("claimedBy").asText());
                                }
                            } else if (response.getStatus() == 409
                                    && mapper.readTree(response.getContentAsString())
                                            .get("code")
                                            .asText()
                                            .equals("ALERT_ALREADY_CLAIMED")) {
                                conflicts.incrementAndGet();
                            }
                            return null;
                        });
            }
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Object> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(19);
        assertThat(winners).hasSize(1);
        assertThat(row(id)).containsEntry("status", "CLAIMED").containsEntry("claimed_by", winners.get(0));
    }

    @Test
    void parallelClaimsReleasesAndResolvesLeaveAConsistentAlert() throws Exception {
        long id = newAlert("OPEN");
        String op1 = token("op01");
        String op2 = token("op02");
        String sup = token("sup01");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                tasks.add(() -> claim(id, op1).andReturn());
                tasks.add(() -> claim(id, op2).andReturn());
                tasks.add(() -> release(id, sup).andReturn());
            }
            tasks.add(() -> resolve(id, sup, note("final")).andReturn());
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> alert = row(id);
        String status = (String) alert.get("status");
        // whatever the order, the alert is in a state that is allowed by the rules
        assertThat(status).isIn("OPEN", "CLAIMED", "RESOLVED");
        if (status.equals("OPEN")) {
            assertThat(alert.get("claimed_by")).isNull();
        } else {
            assertThat(alert.get("claimed_by")).isIn("op01", "op02");
        }
        if (status.equals("RESOLVED")) {
            assertThat(alert.get("resolved_by")).isEqualTo("sup01");
        }
    }
}
