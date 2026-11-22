package io.geoalert.ingest.domain;

import java.time.Instant;
import java.util.List;

/** Port: inspecting the rejected lines later (R6). */
public interface RejectedLineQueries {

    record StoredRejection(
            long id,
            long batchId,
            int lineNo,
            String code,
            String detail,
            String rawLine,
            Instant createdAt) {}

    /** Newest first: rows with an id below {@code beforeId}, at most {@code limit + 1} rows. */
    List<StoredRejection> newestFirst(long beforeId, int limit);
}
