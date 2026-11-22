package io.geoalert.ingest.domain;

import java.util.List;
import java.util.Set;

/** Port: the track, one row per accepted report. */
public interface ReportStore {

    /**
     * Stores the reports whose report id has not been stored before and returns the ids that
     * were stored. The others are duplicates and change nothing (R1). The check and the insert
     * are one atomic step in the database, so parallel requests, and both instances, agree.
     */
    Set<String> insertNew(List<ParsedReport> reports);
}
