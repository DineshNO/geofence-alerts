package com.example.geoalert.ingest.domain;

/** Port: the append-only log of delivered batches. Stats are summed from it. */
public interface BatchLog {

    record BatchRecord(
            long batchId,
            String requestId,
            String instance,
            int received,
            int accepted,
            int duplicates,
            int rejected) {}

    /** Reserves an id so the rejected lines can refer to the batch before its row is written. */
    long nextBatchId();

    /** Adds the row of a batch. Rows are only ever inserted. */
    void record(BatchRecord batch);
}
