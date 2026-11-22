package io.geoalert.ingest.domain;

import java.util.List;

/** Port: where rejected lines are kept so they can be inspected later (R6). */
public interface RejectedLineStore {

    void saveAll(long batchId, List<RejectedLine> lines);
}
