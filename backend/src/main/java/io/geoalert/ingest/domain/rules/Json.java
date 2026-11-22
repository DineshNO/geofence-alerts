package io.geoalert.ingest.domain.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Small readers shared by the rules and by the step that builds the report. */
public final class Json {

    // a timestamp must fit the database column (and be a sane year)
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private Json() {}

    /** Absent, null or an empty string all count as missing. */
    public static boolean isAbsent(JsonNode node) {
        return node == null
                || node.isNull()
                || node.isMissingNode()
                || (node.isTextual() && node.asText().isBlank());
    }

    /** The instant in UTC, or null when the value is not an ISO-8601 instant in the allowed years. */
    public static Instant parseInstant(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            Instant instant =
                    OffsetDateTime.parse(node.asText())
                            .withOffsetSameInstant(ZoneOffset.UTC)
                            .toInstant();
            return instant.isBefore(EARLIEST) || instant.isAfter(LATEST) ? null : instant;
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }
}
