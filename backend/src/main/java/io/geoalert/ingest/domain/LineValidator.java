package io.geoalert.ingest.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.geoalert.ingest.domain.rules.AssetId;
import io.geoalert.ingest.domain.rules.Coordinates;
import io.geoalert.ingest.domain.rules.LineRule;
import io.geoalert.ingest.domain.rules.ReportId;
import io.geoalert.ingest.domain.rules.RequiredFields;
import io.geoalert.ingest.domain.rules.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Judges one line of a batch on its own, with no database and no framework, so one bad line
 * can never affect another (R6). It is strict enough that the database never rejects a line
 * that passed here (text with a NUL character, numbers or years outside the column range).
 *
 * <p>The line is parsed, then run through the {@link LineRule}s by ascending priority (a stable
 * sort, so equal priorities keep the given order): the first problem found is the one reported. A line that passes every rule is turned into a report by
 * {@link ReportAssembler}. The priorities of the standard rules follow the contract: required
 * fields, coordinates, timestamp, then the identifiers. To add a validation, write a rule with a
 * priority and add it to {@link #standard()}; nothing else changes.
 */
public final class LineValidator {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final List<LineRule> rules;
    private final ReportAssembler assembler = new ReportAssembler();

    public LineValidator(List<LineRule> rules) {
        this.rules = rules.stream().sorted(Comparator.comparingInt(LineRule::priority)).toList();
    }

    /** The rules of the contract, in the order they are applied. */
    public static LineValidator standard() {
        return new LineValidator(
                List.of(
                        new RequiredFields(),
                        new Coordinates(),
                        new Timestamp(),
                        new AssetId(),
                        new ReportId()));
    }

    /** Same as {@link #standard()}; kept so existing callers need no change. */
    public LineValidator() {
        this(standard().rules);
    }

    public LineOutcome validate(String line) {
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (Exception e) {
            return new LineOutcome.Rejected(RejectionCode.MALFORMED_JSON, "The line is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            return new LineOutcome.Rejected(
                    RejectionCode.MALFORMED_JSON, "The line is not a JSON object.");
        }
        for (LineRule rule : rules) {
            Optional<LineOutcome.Rejected> problem = rule.check(root);
            if (problem.isPresent()) {
                return problem.get();
            }
        }
        return assembler.assemble(root);
    }
}
