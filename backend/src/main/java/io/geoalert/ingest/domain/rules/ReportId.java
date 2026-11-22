package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.RejectionCode;
import java.util.Optional;

/** reportId is a short string without control characters. */
public final class ReportId implements LineRule {

    @Override
    public int priority() {
        return RulePriority.REPORT_ID;
    }

    private static final int MAX_LENGTH = 64;

    @Override
    public Optional<LineOutcome.Rejected> check(JsonNode line) {
        JsonNode node = line.get("reportId");
        if (!node.isTextual()) {
            return reject("reportId must be a string.");
        }
        String id = node.asText();
        if (id.length() > MAX_LENGTH) {
            return reject("reportId must have at most " + MAX_LENGTH + " characters.");
        }
        if (id.chars().anyMatch(Character::isISOControl)) {
            return reject("reportId must not contain control characters.");
        }
        return Optional.empty();
    }

    private static Optional<LineOutcome.Rejected> reject(String detail) {
        return Optional.of(new LineOutcome.Rejected(RejectionCode.INVALID_FIELD, detail));
    }
}
