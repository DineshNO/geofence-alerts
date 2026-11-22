package com.example.geoalert.alerts;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.geoalert.alerts.domain.AlertStatus;
import com.example.geoalert.alerts.domain.AuditAction;
import com.example.geoalert.alerts.domain.AuditEntry;
import com.example.geoalert.alerts.domain.AuditLog;
import com.example.geoalert.support.FeedClient;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.example.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R5: every change of an alert's state is recorded in the audit trail, in the same transaction as
 * the change. Both happen or neither. A command that changes nothing records nothing.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuditTrailTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    // a spy: the real log, which a test can make fail on demand
    @MockitoSpyBean private AuditLog auditLog;

    private FeedClient feed;
    private static int nextAsset = 0;

    @BeforeEach
    void signIn() throws Exception {
        feed = FeedClient.signIn(mvc, mapper);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private synchronized String newAsset() {
        return "AUD" + (nextAsset++);
    }

    /** Delivers an entry into Z-01 so the system opens an alert, and returns the alert id. */
    private long openedAlert(String asset) throws Exception {
        feed.send(
                line("aud-" + asset + "-a", asset, at(1), 10.0, 20.0),
                line("aud-" + asset + "-b", asset, at(2), 38.2, 168.4));
        return jdbc.queryForObject("SELECT id FROM alerts WHERE asset_id = ?", Long.class, asset);
    }

    private String bearer(String user) throws Exception {
        return Tokens.bearer(Tokens.login(mvc, mapper, user));
    }

    private void command(long id, String action, String user) throws Exception {
        mvc.perform(post("/api/v1/alerts/{id}/" + action, id).header("Authorization", bearer(user)))
                .andReturn();
    }

    private void resolve(long id, String user, String note) throws Exception {
        mvc.perform(
                        post("/api/v1/alerts/{id}/resolve", id)
                                .header("Authorization", bearer(user))
                                .contentType("application/json")
                                .content("{\"note\":\"" + note + "\"}"))
                .andReturn();
    }

    private long auditRows(long alertId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM alert_audit WHERE alert_id = ?", Long.class, alertId);
    }

    // ---- what is recorded

    @Test
    void theSystemOpeningAnAlertIsRecorded() throws Exception {
        long id = openedAlert(newAsset());
        List<AuditEntry> trail = auditLog.forAlert(id);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).actor()).isEqualTo("system");
        assertThat(trail.get(0).action()).isEqualTo(AuditAction.OPENED);
        assertThat(trail.get(0).fromStatus()).isNull();
        assertThat(trail.get(0).toStatus()).isEqualTo(AlertStatus.OPEN);
    }

    @Test
    void everyChangeInTheLifecycleAddsOneEntryInOrder() throws Exception {
        long id = openedAlert(newAsset());
        command(id, "claim", "op01");
        command(id, "release", "op01");
        command(id, "claim", "op02");
        resolve(id, "op02", "all clear");

        List<AuditEntry> trail = auditLog.forAlert(id);
        assertThat(trail)
                .extracting(AuditEntry::action)
                .containsExactly(
                        AuditAction.OPENED,
                        AuditAction.CLAIMED,
                        AuditAction.RELEASED,
                        AuditAction.CLAIMED,
                        AuditAction.RESOLVED);
        assertThat(trail)
                .extracting(AuditEntry::actor)
                .containsExactly("system", "op01", "op01", "op02", "op02");
        assertThat(trail)
                .extracting(AuditEntry::fromStatus)
                .containsExactly(
                        null,
                        AlertStatus.OPEN,
                        AlertStatus.CLAIMED,
                        AlertStatus.OPEN,
                        AlertStatus.CLAIMED);
        assertThat(trail)
                .extracting(AuditEntry::toStatus)
                .containsExactly(
                        AlertStatus.OPEN,
                        AlertStatus.CLAIMED,
                        AlertStatus.OPEN,
                        AlertStatus.CLAIMED,
                        AlertStatus.RESOLVED);
        assertThat(trail).extracting(AuditEntry::at).doesNotContainNull().isSorted();
    }

    @Test
    void aSupervisorsActionIsRecordedUnderTheSupervisor() throws Exception {
        long id = openedAlert(newAsset());
        command(id, "claim", "op01");
        command(id, "release", "sup01");
        assertThat(auditLog.forAlert(id).get(2).actor()).isEqualTo("sup01");
    }

    @Test
    void aRepeatThatChangesNothingRecordsNothing() throws Exception {
        long id = openedAlert(newAsset());
        command(id, "claim", "op01");
        command(id, "claim", "op01"); // repeat by the owner
        resolve(id, "op01", "done");
        resolve(id, "op01", "done again"); // repeat by the same user
        assertThat(auditRows(id)).isEqualTo(3); // OPENED, CLAIMED, RESOLVED
    }

    @Test
    void aRefusedCommandRecordsNothing() throws Exception {
        long id = openedAlert(newAsset());
        command(id, "claim", "op01");
        command(id, "claim", "op02"); // 409
        command(id, "release", "op02"); // 403
        resolve(id, "op02", "not mine"); // 403
        resolve(id, "op01", ""); // 400
        assertThat(auditRows(id)).isEqualTo(2); // OPENED, CLAIMED
    }

    @Test
    void deliveringTheEnteringReportAgainDoesNotOpenOrRecordAnything() throws Exception {
        String asset = newAsset();
        String enter = line("aud-" + asset + "-b", asset, at(2), 38.2, 168.4);
        long id = openedAlert(asset);
        feed.send(enter);
        feed.send(enter);
        assertThat(auditRows(id)).isEqualTo(1);
    }

    @Test
    void parallelClaimsLeaveExactlyOneClaimedEntry() throws Exception {
        long id = openedAlert(newAsset());
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            tokens.add(bearer("op%02d".formatted(i)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (String token : tokens) {
                tasks.add(
                        () ->
                                mvc.perform(
                                                post("/api/v1/alerts/{id}/claim", id)
                                                        .header("Authorization", token))
                                        .andReturn());
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long claimed =
                auditLog.forAlert(id).stream().filter(e -> e.action() == AuditAction.CLAIMED).count();
        assertThat(claimed).isEqualTo(1);
        assertThat(auditRows(id)).isEqualTo(2);
    }

    // ---- both happen or neither

    @Test
    void ifTheAuditEntryCannotBeWrittenTheClaimIsNotStored() throws Exception {
        long id = openedAlert(newAsset());
        doThrow(new IllegalStateException("audit store down"))
                .when(auditLog)
                .record(anyLong(), any(), any(), any(), any());

        mvc.perform(post("/api/v1/alerts/{id}/claim", id).header("Authorization", bearer("op01")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        var alert = jdbc.queryForMap("SELECT status, claimed_by FROM alerts WHERE id = ?", id);
        assertThat(alert).containsEntry("status", "OPEN").containsEntry("claimed_by", null);
        assertThat(auditRows(id)).isEqualTo(1); // only the earlier OPENED
    }

    @Test
    void ifTheAuditEntryCannotBeWrittenReleaseAndResolveAreNotStoredEither() throws Exception {
        long id = openedAlert(newAsset());
        command(id, "claim", "op01");
        doThrow(new IllegalStateException("audit store down"))
                .when(auditLog)
                .record(anyLong(), any(), any(), any(), any());

        mvc.perform(post("/api/v1/alerts/{id}/release", id).header("Authorization", bearer("op01")))
                .andExpect(status().isInternalServerError());
        mvc.perform(
                        post("/api/v1/alerts/{id}/resolve", id)
                                .header("Authorization", bearer("op01"))
                                .contentType("application/json")
                                .content("{\"note\":\"done\"}"))
                .andExpect(status().isInternalServerError());

        var alert = jdbc.queryForMap("SELECT status, claimed_by, resolved_by FROM alerts WHERE id = ?", id);
        assertThat(alert)
                .containsEntry("status", "CLAIMED")
                .containsEntry("claimed_by", "op01")
                .containsEntry("resolved_by", null);
        assertThat(auditRows(id)).isEqualTo(2); // OPENED, CLAIMED
    }

    @Test
    void ifTheOpenedEntryCannotBeWrittenTheWholeBatchIsNotStored() throws Exception {
        String asset = newAsset();
        doThrow(new IllegalStateException("audit store down"))
                .when(auditLog)
                .recordOpened(any());

        String body =
                String.join(
                        "\n",
                        line("aud-fail-" + asset + "-a", asset, at(1), 10.0, 20.0),
                        line("aud-fail-" + asset + "-b", asset, at(2), 38.2, 168.4));
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(Tokens.login(mvc, mapper, FEED_USER)))
                                .header("X-Request-Id", "failed-batch-" + asset)
                                .contentType("application/x-ndjson")
                                .content(body))
                .andExpect(status().isInternalServerError());

        // nothing of the batch survives: not the reports, the position, the alert or the batch row
        assertThat(count("SELECT count(*) FROM reports WHERE asset_id = ?", asset)).isZero();
        assertThat(count("SELECT count(*) FROM asset_current WHERE asset_id = ?", asset)).isZero();
        assertThat(count("SELECT count(*) FROM alerts WHERE asset_id = ?", asset)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM ingest_batches WHERE request_id = ?",
                                "failed-batch-" + asset))
                .isZero();
    }

    private long count(String sql, String arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    // ---- the trail cannot be changed through the application

    @Test
    void theAuditLogOffersNoWayToChangeOrRemoveAnEntry() {
        List<String> names =
                Arrays.stream(AuditLog.class.getMethods()).map(Method::getName).sorted().toList();
        assertThat(names).containsExactlyInAnyOrder("forAlert", "record", "recordOpened");
    }
}
