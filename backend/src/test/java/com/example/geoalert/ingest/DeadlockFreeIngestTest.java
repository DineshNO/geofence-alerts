package com.example.geoalert.ingest;

import static com.example.geoalert.support.FeedClient.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.geoalert.metrics.Metrics;
import com.example.geoalert.support.FeedClient;
import com.example.geoalert.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Batches that carry the same report ids in a different order must not wait on each other in a
 * circle. The feed repeats reports across batches, and two inserts that take the same unique keys in
 * opposite order deadlock; taking them in one fixed order makes that impossible.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class DeadlockFreeIngestTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Metrics metrics;

    private static String at(int second) {
        return Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second).toString();
    }

    @Test
    void batchesWithTheSameReportIdsInOppositeOrderNeverDeadlock() throws Exception {
        FeedClient feed = FeedClient.signIn(mvc, mapper);
        long retriesBefore = metrics.get("ingest_transient_retries_total");
        long failuresBefore = metrics.get("ingest_batch_failures_total");

        int rounds = 12;
        int size = 300;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < rounds; round++) {
                List<String> forward = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    forward.add(line("dl-" + round + "-" + i, "DLK" + (i % 40), at(round * 1000 + i), 10.0, 20.0));
                }
                List<String> backward = new ArrayList<>(forward);
                Collections.reverse(backward);

                List<Callable<Object>> tasks = new ArrayList<>();
                for (int k = 0; k < 4; k++) {
                    tasks.add(() -> feed.send(forward));
                    tasks.add(() -> feed.send(backward));
                }
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    f.get(120, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Long stored = jdbc.queryForObject("SELECT count(*) FROM reports WHERE report_id LIKE 'dl-%'", Long.class);
        assertThat(stored).isEqualTo((long) rounds * size);
        // a deadlock would show as a retry, and an exhausted one as a failed batch
        assertThat(metrics.get("ingest_transient_retries_total") - retriesBefore).isZero();
        assertThat(metrics.get("ingest_batch_failures_total") - failuresBefore).isZero();
    }
}
