package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.RejectionCode;
import java.util.List;
import java.util.Optional;

/** The fields the contract requires must be present and not empty. */
public final class RequiredFields implements LineRule {

    @Override
    public int priority() {
        return RulePriority.REQUIRED_FIELDS;
    }

    private static final List<String> REQUIRED =
            List.of("reportId", "assetId", "observedAt", "lat", "lon");

    @Override
    public Optional<LineOutcome.Rejected> check(JsonNode line) {
        for (String field : REQUIRED) {
            if (Json.isAbsent(line.get(field))) {
                return Optional.of(
                        new LineOutcome.Rejected(RejectionCode.MISSING_FIELD, field + " is required."));
            }
        }
        return Optional.empty();
    }
}
