package io.geoalert.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.ingest.application.rules.AssetId;
import io.geoalert.ingest.application.rules.Coordinates;
import io.geoalert.ingest.application.rules.ReportId;
import io.geoalert.ingest.application.rules.RequiredFields;
import io.geoalert.ingest.application.rules.RulePriority;
import io.geoalert.ingest.application.rules.Timestamp;
import io.geoalert.ingest.domain.rules.LineRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the rules are put together: by priority, extensible without touching the others. */
class LineRulePipelineTest {

    private static final String GOOD =
            "{\"reportId\":\"r1\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:00Z\",\"lat\":1,\"lon\":2}";
    // breaks both the coordinate rule and the asset id rule
    private static final String TWO_PROBLEMS =
            "{\"reportId\":\"r1\",\"assetId\":\"x\",\"observedAt\":\"2026-11-02T06:00:00Z\",\"lat\":99,\"lon\":2}";

    private static LineRule rule(int priority, String detail) {
        return new LineRule() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Optional<LineOutcome.Rejected> check(com.fasterxml.jackson.databind.JsonNode line) {
                return Optional.of(new LineOutcome.Rejected(RejectionCode.INVALID_FIELD, detail));
            }
        };
    }

    @Test
    void theOrderComesFromThePriorityNotFromThePositionInTheList() {
        List<LineRule> shuffled =
                List.of(new ReportId(), new AssetId(), new Timestamp(), new Coordinates(), new RequiredFields());
        LineOutcome outcome = new LineValidator(shuffled).validate(TWO_PROBLEMS);
        // coordinates (200) come before the asset id (400), whatever the list order
        assertThat(((LineOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_COORDINATE);
    }

    @Test
    void aNewRuleSlotsInBetweenByItsPriority() {
        List<LineRule> rules = new ArrayList<>(List.of(
                new RequiredFields(), new Coordinates(), new Timestamp(), new AssetId(), new ReportId()));
        rules.add(rule(RulePriority.COORDINATES + 10, "custom, after coordinates"));
        // coordinates still win when they fail ...
        assertThat(((LineOutcome.Rejected) new LineValidator(rules).validate(TWO_PROBLEMS)).code())
                .isEqualTo(RejectionCode.INVALID_COORDINATE);
        // ... and the new rule runs before the asset id rule when the coordinates are fine
        String badAssetOnly = TWO_PROBLEMS.replace("\"lat\":99", "\"lat\":1");
        assertThat(((LineOutcome.Rejected) new LineValidator(rules).validate(badAssetOnly)).detail())
                .isEqualTo("custom, after coordinates");
    }

    @Test
    void aNewRuleCanRunFirst() {
        List<LineRule> rules = new ArrayList<>(StandardRules.list());
        rules.add(rule(1, "first"));
        assertThat(((LineOutcome.Rejected) new LineValidator(rules).validate(GOOD)).detail()).isEqualTo("first");
    }

    @Test
    void theStandardRulesHaveIncreasingPriorityInTheContractOrder() {
        List<Integer> priorities =
                List.of(
                        RulePriority.REQUIRED_FIELDS, RulePriority.COORDINATES, RulePriority.TIMESTAMP,
                        RulePriority.ASSET_ID, RulePriority.REPORT_ID);
        assertThat(priorities).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void aLineThatPassesEveryRuleBecomesAReport() {
        assertThat(StandardRules.validator().validate(GOOD))
                .isInstanceOf(LineOutcome.Valid.class);
    }
}
