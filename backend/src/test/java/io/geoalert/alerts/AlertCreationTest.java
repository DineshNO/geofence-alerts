package io.geoalert.alerts;

import static io.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.alerts.domain.AlertStore;
import io.geoalert.alerts.domain.NewAlert;
import io.geoalert.support.FeedClient;
import io.geoalert.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

/** The alert rule, and R3: never more than one OPEN or CLAIMED alert per asset and zone. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AlertCreationTest extends PostgresIntegrationTest {

    // Z-01 covers lon 168.0-168.8, lat 38.0-38.6; far away from every zone is (10, 20)
    private static final double[] IN_Z01 = {38.2, 168.4};
    private static final double[] OUTSIDE = {10.0, 20.0};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AlertStore alertStore;

    private FeedClient feed;

    @BeforeEach
    void signIn() throws Exception {
        feed = FeedClient.signIn(mvc, mapper);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private static String report(String id, String asset, int second, double[] position) {
        return line(id, asset, at(second), position[0], position[1]);
    }

    private List<Map<String, Object>> alertsOf(String assetId) {
        return jdbc.queryForList(
                "SELECT zone_id, status, trigger_report_id, trigger_observed_at FROM alerts"
                        + " WHERE asset_id = ? ORDER BY id",
                assetId);
    }

    @Test
    void enteringARestrictedZoneRaisesOneOpenAlert() throws Exception {
        feed.send(report("ac-1a", "ACA", 1, OUTSIDE));
        feed.send(report("ac-1b", "ACA", 2, IN_Z01));
        List<Map<String, Object>> alerts = alertsOf("ACA");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0))
                .containsEntry("zone_id", "Z-01")
                .containsEntry("status", "OPEN")
                .containsEntry("trigger_report_id", "ac-1b");
    }

    @Test
    void aFirstEverReportInsideAZoneRaisesAnAlertBecauseThereWasNoPosition() throws Exception {
        feed.send(report("ac-2", "ACB", 1, IN_Z01));
        assertThat(alertsOf("ACB")).hasSize(1);
    }

    @Test
    void stayingInsideRaisesNothingNew() throws Exception {
        feed.send(report("ac-3a", "ACC", 1, IN_Z01));
        feed.send(report("ac-3b", "ACC", 2, IN_Z01));
        feed.send(report("ac-3c", "ACC", 3, IN_Z01));
        assertThat(alertsOf("ACC")).hasSize(1);
    }

    @Test
    void enteringAgainWhileTheAlertIsStillActiveRaisesNothing() throws Exception {
        feed.send(report("ac-4a", "ACD", 1, IN_Z01)); // enters: alert 1
        feed.send(report("ac-4b", "ACD", 2, OUTSIDE)); // leaves
        feed.send(report("ac-4c", "ACD", 3, IN_Z01)); // enters again, alert still OPEN
        assertThat(alertsOf("ACD")).hasSize(1);

        jdbc.update("UPDATE alerts SET status = 'CLAIMED' WHERE asset_id = 'ACD'");
        feed.send(report("ac-4d", "ACD", 4, OUTSIDE));
        feed.send(report("ac-4e", "ACD", 5, IN_Z01)); // still CLAIMED: nothing
        assertThat(alertsOf("ACD")).hasSize(1);
    }

    @Test
    void afterResolvingTheNextEntryRaisesANewAlert() throws Exception {
        feed.send(report("ac-5a", "ACE", 1, IN_Z01));
        jdbc.update("UPDATE alerts SET status = 'RESOLVED' WHERE asset_id = 'ACE'");
        // simply staying inside after the resolution raises nothing
        feed.send(report("ac-5b", "ACE", 2, IN_Z01));
        assertThat(alertsOf("ACE")).hasSize(1);
        // leaving and entering again does
        feed.send(report("ac-5c", "ACE", 3, OUTSIDE));
        feed.send(report("ac-5d", "ACE", 4, IN_Z01));
        List<Map<String, Object>> alerts = alertsOf("ACE");
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(1)).containsEntry("status", "OPEN").containsEntry("trigger_report_id", "ac-5d");
    }

    @Test
    void anAdvisoryZoneNeverRaisesAnAlert() throws Exception {
        feed.send(report("ac-6", "ACF", 1, new double[] {40.85, 169.9})); // inside Z-09 (advisory)
        assertThat(alertsOf("ACF")).isEmpty();
    }

    @Test
    void overlappingZonesEachRaiseTheirOwnAlert() throws Exception {
        feed.send(report("ac-7", "ACG", 1, new double[] {32.6, 174.8})); // Z-04 and Z-05
        List<Map<String, Object>> alerts = alertsOf("ACG");
        assertThat(alerts).extracting(a -> a.get("zone_id")).containsExactlyInAnyOrder("Z-04", "Z-05");
    }

    @Test
    void aLateReportInsideAZoneNeverRaisesAnAlert() throws Exception {
        feed.send(report("ac-8a", "ACH", 10, OUTSIDE));
        feed.send(report("ac-8b", "ACH", 5, IN_Z01)); // older than the position: late
        assertThat(alertsOf("ACH")).isEmpty();
    }

    @Test
    void theOrderOfArrivalDecidesWhichReportIsLate() throws Exception {
        // delivered in time order: outside then inside -> an alert
        feed.send(report("ac-9a", "ACI", 1, OUTSIDE), report("ac-9b", "ACI", 2, IN_Z01));
        assertThat(alertsOf("ACI")).hasSize(1);
        // delivered newest first: the inside report is older than the position, so it is late
        feed.send(report("ac-9d", "ACJ", 2, OUTSIDE), report("ac-9c", "ACJ", 1, IN_Z01));
        assertThat(alertsOf("ACJ")).isEmpty();
    }

    @Test
    void aDuplicateNeverRaisesAnotherAlert() throws Exception {
        String enter = report("ac-10b", "ACK", 2, IN_Z01);
        feed.send(report("ac-10a", "ACK", 1, OUTSIDE), enter);
        jdbc.update("UPDATE alerts SET status = 'RESOLVED' WHERE asset_id = 'ACK'");
        feed.send(report("ac-10c", "ACK", 3, OUTSIDE));
        // the old entering report is delivered again: nothing changes, no new alert
        var again = feed.send(enter);
        assertThat(again.get("duplicates").asInt()).isEqualTo(1);
        assertThat(alertsOf("ACK")).hasSize(1);
    }

    @Test
    void leavingAndEnteringInsideOneBatchRaisesOneAlertWhileItIsActive() throws Exception {
        feed.send(
                report("ac-11a", "ACL", 1, IN_Z01),
                report("ac-11b", "ACL", 2, OUTSIDE),
                report("ac-11c", "ACL", 3, IN_Z01));
        assertThat(alertsOf("ACL")).hasSize(1);
    }

    @Test
    void entersAZoneAcrossThe180Meridian() throws Exception {
        feed.send(report("ac-12", "ACM", 1, new double[] {34.5, -179.8})); // Z-08
        assertThat(alertsOf("ACM")).extracting(a -> a.get("zone_id")).containsExactly("Z-08");
    }

    // ---- R3 under parallel requests

    @Test
    void parallelBatchesThatAllEnterTheSameZoneRaiseOneAlert() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                int n = k;
                tasks.add(
                        () ->
                                feed.send(
                                        report("ac-par-" + n + "a", "ACP", 100 * n, OUTSIDE),
                                        report("ac-par-" + n + "b", "ACP", 100 * n + 1, IN_Z01)));
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(alertsOf("ACP")).hasSizeLessThanOrEqualTo(1);
        assertThat(alertsOf("ACP")).hasSize(1);
    }

    @Test
    void manyWritersCreatingTheSameAlertAtOnceCreateExactlyOne() throws Exception {
        // straight at the store, one autocommit statement per thread: the database decides
        NewAlert due = new NewAlert("ACQ", "Z-01", "ac-race", Instant.parse("2026-11-02T06:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> alertStore.insertNew(List.of(due)).size());
            }
            int created = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                created += f.get(60, TimeUnit.SECONDS);
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(alertsOf("ACQ")).hasSize(1);
    }
}
