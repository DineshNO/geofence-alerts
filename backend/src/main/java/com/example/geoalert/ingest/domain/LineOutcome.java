package com.example.geoalert.ingest.domain;

import java.util.List;

/** The result of judging one line: either a valid report or a rejection with its reason. */
public sealed interface LineOutcome {

    /**
     * A valid report. {@code replacedFields} names the optional fields whose value was not
     * usable and was stored as NULL; the report is kept (optional fields never reject a line).
     */
    record Valid(ParsedReport report, List<String> replacedFields) implements LineOutcome {}

    record Rejected(RejectionCode code, String detail) implements LineOutcome {}
}
