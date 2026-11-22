package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.RejectionCode;
import java.util.Optional;

/** lat and lon are JSON numbers inside the valid range. */
public final class Coordinates implements LineRule {

    @Override
    public int priority() {
        return RulePriority.COORDINATES;
    }

    @Override
    public Optional<LineOutcome.Rejected> check(JsonNode line) {
        JsonNode latNode = line.get("lat");
        JsonNode lonNode = line.get("lon");
        if (!latNode.isNumber() || !lonNode.isNumber()) {
            return reject("lat and lon must be numbers.");
        }
        double lat = latNode.asDouble();
        double lon = lonNode.asDouble();
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            return reject("lat must be from -90 to 90.");
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            return reject("lon must be from -180 to 180.");
        }
        return Optional.empty();
    }

    private static Optional<LineOutcome.Rejected> reject(String detail) {
        return Optional.of(new LineOutcome.Rejected(RejectionCode.INVALID_COORDINATE, detail));
    }
}
