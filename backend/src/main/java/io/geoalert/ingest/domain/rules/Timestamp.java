package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.RejectionCode;
import java.util.Optional;

/** observedAt is an ISO-8601 instant that the database can store. */
public final class Timestamp implements LineRule {

    @Override
    public int priority() {
        return RulePriority.TIMESTAMP;
    }

    @Override
    public Optional<LineOutcome.Rejected> check(JsonNode line) {
        if (Json.parseInstant(line.get("observedAt")) == null) {
            return Optional.of(
                    new LineOutcome.Rejected(
                            RejectionCode.INVALID_TIMESTAMP, "observedAt must be an ISO-8601 instant."));
        }
        return Optional.empty();
    }
}
