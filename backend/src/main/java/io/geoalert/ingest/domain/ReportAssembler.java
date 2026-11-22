package io.geoalert.ingest.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.geoalert.ingest.domain.rules.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the report from a line that passed every rule, so the required fields can be read
 * without checking again. Optional fields never reject a line: text is stored as received, a
 * number or numeric string is accepted, and anything the database cannot hold is replaced with
 * NULL and named in the outcome.
 */
final class ReportAssembler {

    private static final Pattern DECIMAL =
            Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

    LineOutcome.Valid assemble(JsonNode line) {
        List<String> replaced = new ArrayList<>();
        ParsedReport report =
                new ParsedReport(
                        line.get("reportId").asText(),
                        line.get("assetId").asText(),
                        Json.parseInstant(line.get("observedAt")),
                        line.get("lat").asDouble(),
                        line.get("lon").asDouble(),
                        optionalText(line.get("kind"), "kind", replaced),
                        optionalNumber(line.get("speedKn"), "speedKn", replaced),
                        optionalNumber(line.get("headingDeg"), "headingDeg", replaced),
                        optionalText(line.get("source"), "source", replaced));
        return new LineOutcome.Valid(report, List.copyOf(replaced));
    }

    /** Text with a NUL character, or not a plain value, is replaced with NULL. */
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

    /** A number or a numeric string such as "14.1" is accepted (a negative speed is kept). */
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
