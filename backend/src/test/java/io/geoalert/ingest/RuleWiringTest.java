package io.geoalert.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.LineValidator;
import io.geoalert.ingest.domain.RejectionCode;
import io.geoalert.ingest.domain.rules.LineRule;
import io.geoalert.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spring finds every rule by itself; nobody lists them. */
class RuleWiringTest extends PostgresIntegrationTest {

    @Autowired private List<LineRule> rules;
    @Autowired private LineValidator validator;

    @Test
    void everyRuleComponentIsFoundAndTheValidatorUsesThem() {
        assertThat(rules).extracting(r -> r.getClass().getSimpleName())
                .containsExactlyInAnyOrder("RequiredFields", "Coordinates", "Timestamp", "AssetId", "ReportId");
        LineOutcome outcome = validator.validate(
                "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:00Z\",\"lat\":99,\"lon\":2}");
        assertThat(((LineOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_COORDINATE);
    }
}
