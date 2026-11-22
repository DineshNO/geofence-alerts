package io.geoalert.ingest.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Judges one line of a batch on its own, with no database and no framework, so one bad line
 * can never affect another (R6). It is strict enough that the database never rejects a line
 * that passed here (text with a NUL character, numbers or years outside the column range).
 *
 * <p>Order of the checks follows the contract: the line is one JSON object, the required
 * fields are present, the coordinates, the timestamp, then the identifiers. The first problem
 * found is the one reported. Optional fields never reject a line.
 */
public final class LineValidator {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern ASSET_ID = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,31}$");
    private static final Pattern DECIMAL =
            Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final int MAX_REPORT_ID = 64;

    // a timestamp must fit the database column (and be a sane year)
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    public LineOutcome validate(String line) {
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (Exception e) {
            return reject(RejectionCode.MALFORMED_JSON, "The line is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            return reject(RejectionCode.MALFORMED_JSON, "The line is not a JSON object.");
        }

        for (String field : List.of("reportId", "assetId", "observedAt", "lat", "lon")) {
            if (isAbsent(root.get(field))) {
                return reject(RejectionCode.MISSING_FIELD, field + " is required.");
            }
        }

        JsonNode latNode = root.get("lat");
        JsonNode lonNode = root.get("lon");
        if (!latNode.isNumber() || !lonNode.isNumber()) {
            return reject(RejectionCode.INVALID_COORDINATE, "lat and lon must be numbers.");
        }
        double lat = latNode.asDouble();
        double lon = lonNode.asDouble();
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            return reject(RejectionCode.INVALID_COORDINATE, "lat must be from -90 to 90.");
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            return reject(RejectionCode.INVALID_COORDINATE, "lon must be from -180 to 180.");
        }

        Instant observedAt = parseInstant(root.get("observedAt"));
        if (observedAt == null) {
            return reject(
                    RejectionCode.INVALID_TIMESTAMP, "observedAt must be an ISO-8601 instant.");
        }

        JsonNode assetNode = root.get("assetId");
        if (!assetNode.isTextual() || !ASSET_ID.matcher(assetNode.asText()).matches()) {
            return reject(
                    RejectionCode.INVALID_FIELD,
                    "assetId must match ^[A-Z0-9][A-Z0-9-]{2,31}$.");
        }
        JsonNode reportNode = root.get("reportId");
        if (!reportNode.isTextual()) {
            return reject(RejectionCode.INVALID_FIELD, "reportId must be a string.");
        }
        String reportId = reportNode.asText();
        if (reportId.length() > MAX_REPORT_ID) {
            return reject(
                    RejectionCode.INVALID_FIELD,
                    "reportId must have at most " + MAX_REPORT_ID + " characters.");
        }
        if (reportId.chars().anyMatch(Character::isISOControl)) {
            return reject(
                    RejectionCode.INVALID_FIELD, "reportId must not contain control characters.");
        }

        List<String> replaced = new ArrayList<>();
        String kind = optionalText(root.get("kind"), "kind", replaced);
        String source = optionalText(root.get("source"), "source", replaced);
        Double speed = optionalNumber(root.get("speedKn"), "speedKn", replaced);
        Double heading = optionalNumber(root.get("headingDeg"), "headingDeg", replaced);

        return new LineOutcome.Valid(
                new ParsedReport(
                        reportId,
                        assetNode.asText(),
                        observedAt,
                        lat,
                        lon,
                        kind,
                        speed,
                        heading,
                        source),
                List.copyOf(replaced));
    }

    private static LineOutcome reject(RejectionCode code, String detail) {
        return new LineOutcome.Rejected(code, detail);
    }

    /** Absent, null or an empty string all count as missing. */
    private static boolean isAbsent(JsonNode node) {
        return node == null
                || node.isNull()
                || node.isMissingNode()
                || (node.isTextual() && node.asText().isBlank());
    }

    private static Instant parseInstant(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            Instant instant = OffsetDateTime.parse(node.asText()).withOffsetSameInstant(ZoneOffset.UTC).toInstant();
            return instant.isBefore(EARLIEST) || instant.isAfter(LATEST) ? null : instant;
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * Optional text is stored as received. A value the database cannot hold (a NUL character)
     * or that is not a plain value is replaced with NULL, and the line is kept.
     */
    private static String optionalText(JsonNode node, String name, List<String> replaced) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isValueNode()) {
            replaced.add(name);
            return null;
        }
        String text = node.asText();
        if (text.indexOf('\u0000') >= 0) {
            replaced.add(name);
            return null;
        }
        return text;
    }

    /**
     * Optional numbers are stored as given (a negative speed is kept). A number, or a numeric
     * string such as "14.1", is accepted; anything that is not a finite decimal is replaced
     * with NULL and the line is kept.
     */
    private static Double optionalNumber(JsonNode node, String name, List<String> replaced) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        Double value = null;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual() && DECIMAL.matcher(node.asText().trim()).matches()) {
            value = Double.parseDouble(node.asText().trim());
        }
        if (value == null || !Double.isFinite(value)) {
            replaced.add(name);
            return null;
        }
        return value;
    }
}
