package io.geoalert.ingest.adapter.jdbc;

import io.geoalert.ingest.domain.BatchLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBatchLog implements BatchLog {

    private final JdbcTemplate jdbc;

    public JdbcBatchLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextBatchId() {
        return jdbc.queryForObject("SELECT nextval('ingest_batch_seq')", Long.class);
    }

    @Override
    public void record(BatchRecord batch) {
        jdbc.update(
                "INSERT INTO ingest_batches (id, request_id, instance, received, accepted,"
                        + " duplicates, rejected) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batch.batchId(),
                batch.requestId(),
                batch.instance(),
                batch.received(),
                batch.accepted(),
                batch.duplicates(),
                batch.rejected());
    }
}
