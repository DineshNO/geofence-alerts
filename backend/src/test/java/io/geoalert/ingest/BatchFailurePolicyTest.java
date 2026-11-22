package io.geoalert.ingest;

import static io.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.geoalert.alerts.application.AlertOpening;
import io.geoalert.ingest.domain.ReportStore;
import io.geoalert.metrics.Metrics;
import io.geoalert.support.PostgresIntegrationTest;
import io.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * R6 and the failure policy: a database failure fails the whole batch and stores nothing; only a
 * transient failure is retried, and a batch that fails for good is raised as an operational alert.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BatchFailurePolicyTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Metrics metrics;

    @MockitoSpyBean private ReportStore reportStore;
    @MockitoSpyBean private AlertOpening alertOpening;

    private String feedToken;
    private static int nextAsset = 0;

    @BeforeEach
    void signIn() throws Exception {
        feedToken = Tokens.login(mvc, mapper, FEED_USER);
    }

    private synchronized String newAsset() {
        return "FAIL" + (nextAsset++);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private ResultActions deliver(String requestId, String... lines) throws Exception {
        return mvc.perform(
                post("/api/v1/reports/batch")
                        .header("Authorization", Tokens.bearer(feedToken))
                        .header("X-Request-Id", requestId)
                        .contentType("application/x-ndjson")
                        .content(String.join("\n", lines)));
    }

    private long stored(String asset) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE asset_id = ?", Long.class, asset);
    }

    private static SQLException sql(String state) {
        return new SQLException("test failure", state);
    }

    // ---- transient failures are retried

    @Test
    void aDeadlockIsRetriedAndTheBatchThenSucceeds() throws Exception {
        String asset = newAsset();
        doThrow(new CannotAcquireLockException("deadlock", sql("40P01")))
                .doThrow(new CannotAcquireLockException("deadlock", sql("40P01")))
                .doCallRealMethod()
                .when(reportStore)
                .insertNew(any());
        long retriesBefore = metrics.get("ingest_transient_retries_total");
        long failuresBefore = metrics.get("ingest_batch_failures_total");

        deliver("retry-" + asset, line("f-" + asset, asset, at(1), 10.0, 20.0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(stored(asset)).isEqualTo(1);
        assertThat(metrics.get("ingest_transient_retries_total") - retriesBefore).isEqualTo(2);
        assertThat(metrics.get("ingest_batch_failures_total")).isEqualTo(failuresBefore);
    }

    @Test
    void aTransientFailureThatKeepsHappeningFailsTheBatchAfterTheLastAttempt() throws Exception {
        String asset = newAsset();
        doThrow(new CannotAcquireLockException("deadlock", sql("40P01")))
                .when(reportStore)
                .insertNew(any());
        long failuresBefore = metrics.get("ingest_batch_failures_total");

        deliver("exhausted-" + asset, line("f-" + asset, asset, at(1), 10.0, 20.0))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        verify(reportStore, times(4)).insertNew(any()); // the default is 4 attempts
        assertThat(stored(asset)).isZero();
        assertThat(metrics.get("ingest_batch_failures_total") - failuresBefore).isEqualTo(1);
    }

    // ---- failures that cannot pass are not retried

    @Test
    void aSchemaErrorFailsTheBatchAtOnceWithoutRetry() throws Exception {
        String asset = newAsset();
        doThrow(new BadSqlGrammarException("insert", "select", sql("42703")))
                .when(reportStore)
                .insertNew(any());
        long retriesBefore = metrics.get("ingest_transient_retries_total");

        deliver("schema-" + asset, line("f-" + asset, asset, at(1), 10.0, 20.0))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        verify(reportStore, times(1)).insertNew(any());
        assertThat(metrics.get("ingest_transient_retries_total")).isEqualTo(retriesBefore);
        assertThat(stored(asset)).isZero();
    }

    @Test
    void anIntegrityOrDataErrorIsNotRetriedEither() throws Exception {
        String asset = newAsset();
        doThrow(new DataIntegrityViolationException("data", sql("22021")))
                .when(reportStore)
                .insertNew(any());
        deliver("data-" + asset, line("f-" + asset, asset, at(1), 10.0, 20.0))
                .andExpect(status().isInternalServerError());
        verify(reportStore, times(1)).insertNew(any());
    }

    @Test
    void theErrorResponseNeverRevealsTheCause() throws Exception {
        String asset = newAsset();
        doThrow(new BadSqlGrammarException("insert into reports", "select secret", sql("42703")))
                .when(reportStore)
                .insertNew(any());
        String body =
                deliver("hide-" + asset, line("f-" + asset, asset, at(1), 10.0, 20.0))
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("secret").doesNotContain("select").doesNotContain("42703");
    }

    // ---- all or nothing

    @Test
    void aFailureAfterTheReportsAreInsertedLeavesNothingBehind() throws Exception {
        String asset = newAsset();
        // the reports are inserted for real; the step after them then fails
        doThrow(new IllegalStateException("fails after the reports insert"))
                .when(alertOpening)
                .open(any());

        deliver(
                        "after-" + asset,
                        line("f-" + asset + "-a", asset, at(1), 10.0, 20.0),
                        line("f-" + asset + "-b", asset, at(2), 38.2, 168.4),
                        "{this line is bad")
                .andExpect(status().isInternalServerError());

        assertThat(stored(asset)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM asset_current WHERE asset_id = ?",
                                Long.class,
                                asset))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ingest_batches WHERE request_id = ?",
                                Long.class,
                                "after-" + asset))
                .isZero();
        // the rejected line of the failed batch is not kept either, so a resend counts it once
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM rejected_lines WHERE raw_line = ?",
                                Long.class,
                                "{this line is bad"))
                .isZero();
    }

    @Test
    void sendingTheFailedBatchAgainStoresItOnce() throws Exception {
        String asset = newAsset();
        String[] lines = {
            line("f-" + asset + "-a", asset, at(1), 10.0, 20.0),
            line("f-" + asset + "-b", asset, at(2), 38.2, 168.4)
        };
        doThrow(new IllegalStateException("fails once"))
                .doCallRealMethod()
                .when(alertOpening)
                .open(any());

        deliver("again-" + asset, lines).andExpect(status().isInternalServerError());
        // the sender sends the same batch again: stored once, as if the failure never happened
        deliver("again-" + asset, lines)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2));
        assertThat(stored(asset)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM alerts WHERE asset_id = ?", Long.class, asset))
                .isEqualTo(1);
    }

    // ---- the counters can be read without an external service

    @Test
    void theCountersAreReadableByASignedInUserAndNotByAnonymousCallers() throws Exception {
        String asset = newAsset();
        deliver(
                        "metrics-" + asset,
                        line("f-" + asset, asset, at(1), 10.0, 20.0),
                        "not json")
                .andExpect(status().isOk());

        mvc.perform(get("/metrics")).andExpect(status().isUnauthorized());
        String body =
                mvc.perform(get("/metrics").header("Authorization", Tokens.bearer(feedToken)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .contains("ingest_batches_total ")
                .contains("ingest_lines_received_total ")
                .contains("ingest_lines_rejected_total ");
    }
}
