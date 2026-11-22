package io.geoalert.ingest.application.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.RejectionCode;
import io.geoalert.ingest.domain.rules.LineRule;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Pattern;

/** assetId is a string of the allowed shape. */
@Component
public final class AssetId implements LineRule {

    @Override
    public int priority() {
        return RulePriority.ASSET_ID;
    }

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,31}$");

    @Override
    public Optional<LineOutcome.Rejected> check(JsonNode line) {
        JsonNode node = line.get("assetId");
        if (!node.isTextual() || !PATTERN.matcher(node.asText()).matches()) {
            return Optional.of(
                    new LineOutcome.Rejected(
                            RejectionCode.INVALID_FIELD, "assetId must match ^[A-Z0-9][A-Z0-9-]{2,31}$."));
        }
        return Optional.empty();
    }
}
