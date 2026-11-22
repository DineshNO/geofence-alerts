package io.geoalert.positions;

import static io.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.support.FeedClient;
import io.geoalert.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** R2: the current position is the newest report by observation time, whatever the arrival order. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PositionOrderTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private FeedClient feed;

    @BeforeEach
    void signIn() throws Exception {
        feed = FeedClient.signIn(mvc, mapper);
    }

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    private Map<String, Object> current(String assetId) {
        return jdbc.queryForMap(
                "SELECT report_id, observed_at, lat, lon FROM asset_current WHERE asset_id = ?",
                assetId);
    }

    private long tracked(String assetId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE asset_id = ?", Long.class, assetId);
    }

    @Test
    void theFirstReportCreatesTheAssetAndItsPosition() throws Exception {
        feed.send(line("r2a-1", "R2A", at(10), 11.0, 22.0));
        assertThat(current("R2A")).containsEntry("report_id", "r2a-1");
        assertThat(current("R2A").get("lat")).isEqualTo(11.0);
        assertThat(tracked("R2A")).isEqualTo(1);
    }

    @Test
    void aNewerReportMovesThePosition() throws Exception {
        feed.send(line("r2b-1", "R2B", at(10), 1.0, 1.0));
        feed.send(line("r2b-2", "R2B", at(20), 2.0, 2.0));
        assertThat(current("R2B")).containsEntry("report_id", "r2b-2");
    }

    @Test
    void aLateReportIsStoredInTheTrackButNeverMovesThePosition() throws Exception {
        feed.send(line("r2c-1", "R2C", at(20), 2.0, 2.0));
        var late = feed.send(line("r2c-2", "R2C", at(10), 1.0, 1.0));
        assertThat(late.get("accepted").asInt()).isEqualTo(1);
        assertThat(current("R2C")).containsEntry("report_id", "r2c-1");
        assertThat(current("R2C").get("lat")).isEqualTo(2.0);
        assertThat(tracked("R2C")).isEqualTo(2);
    }

    @Test
    void aReportWithTheSameTimeDoesNotMoveThePosition() throws Exception {
        feed.send(line("r2d-1", "R2D", at(10), 1.0, 1.0));
        feed.send(line("r2d-2", "R2D", at(10), 9.0, 9.0));
        assertThat(current("R2D")).containsEntry("report_id", "r2d-1");
        assertThat(tracked("R2D")).isEqualTo(2);
    }

    @Test
    void reportsOfOneAssetInOneBatchEndAtTheNewestWhateverTheLineOrder() throws Exception {
        feed.send(
                line("r2e-3", "R2E", at(30), 3.0, 3.0),
                line("r2e-1", "R2E", at(10), 1.0, 1.0),
                line("r2e-2", "R2E", at(20), 2.0, 2.0));
        assertThat(current("R2E")).containsEntry("report_id", "r2e-3");

        feed.send(
                line("r2f-1", "R2F", at(10), 1.0, 1.0),
                line("r2f-2", "R2F", at(20), 2.0, 2.0),
                line("r2f-3", "R2F", at(30), 3.0, 3.0));
        assertThat(current("R2F")).containsEntry("report_id", "r2f-3");
        assertThat(tracked("R2E")).isEqualTo(3);
        assertThat(tracked("R2F")).isEqualTo(3);
    }

    @Test
    void aDuplicateNeverMovesThePosition() throws Exception {
        String first = line("r2g-1", "R2G", at(10), 1.0, 1.0);
        String second = line("r2g-2", "R2G", at(20), 2.0, 2.0);
        feed.send(first, second);
        // the older report is delivered again after the newer one: nothing changes
        var again = feed.send(first);
        assertThat(again.get("duplicates").asInt()).isEqualTo(1);
        assertThat(current("R2G")).containsEntry("report_id", "r2g-2");
    }

    @Test
    void severalAssetsInOneBatchAreUpdatedIndependently() throws Exception {
        feed.send(
                line("r2h-1", "R2H1", at(10), 1.0, 1.0),
                line("r2h-2", "R2H2", at(10), 2.0, 2.0),
                line("r2h-3", "R2H1", at(20), 3.0, 3.0));
        assertThat(current("R2H1")).containsEntry("report_id", "r2h-3");
        assertThat(current("R2H2")).containsEntry("report_id", "r2h-2");
    }

    @Test
    void reportsOfOneAssetShuffledAcrossParallelBatchesEndAtTheNewest() throws Exception {
        int total = 200;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            lines.add(line("r2p-" + i, "R2P", at(i), i * 0.4, i * 0.4)); // stays within +-90
        }
        Collections.shuffle(lines, new Random(42));
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < total; i += 10) {
            batches.add(lines.subList(i, i + 10));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (List<String> batch : batches) {
                tasks.add(() -> feed.send(batch));
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tracked("R2P")).isEqualTo(total);
        assertThat(current("R2P")).containsEntry("report_id", "r2p-" + (total - 1));
        assertThat(current("R2P").get("lat")).isEqualTo((total - 1) * 0.4);
    }

    @Test
    void parallelBatchesTouchingTheSameAssetsInOppositeOrderDoNotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                List<String> lines =
                        new ArrayList<>(
                                IntStream.range(0, 5)
                                        .mapToObj(
                                                a ->
                                                        line(
                                                                "r2q-" + n + "-" + a,
                                                                "R2Q" + a,
                                                                at(n),
                                                                a,
                                                                n))
                                        .toList());
                if (n % 2 == 1) {
                    Collections.reverse(lines);
                }
                tasks.add(() -> feed.send(lines));
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (int a = 0; a < 5; a++) {
            assertThat(tracked("R2Q" + a)).isEqualTo(40);
            assertThat(current("R2Q" + a)).containsEntry("report_id", "r2q-39-" + a);
        }
    }

    @Test
    void everyAssetInTheTrackHasExactlyOneCurrentRow() throws Exception {
        feed.send(
                line("r2z-1", "R2Z1", at(1), 1.0, 1.0),
                line("r2z-2", "R2Z2", at(2), 2.0, 2.0),
                line("r2z-3", "R2Z1", at(3), 3.0, 3.0));
        Long orphans =
                jdbc.queryForObject(
                        "SELECT count(*) FROM (SELECT DISTINCT asset_id FROM reports) t"
                                + " WHERE NOT EXISTS (SELECT 1 FROM asset_current c"
                                + " WHERE c.asset_id = t.asset_id)",
                        Long.class);
        assertThat(orphans).isZero();
    }
}
