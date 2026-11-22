package io.geoalert.ingest.domain;

import io.geoalert.ingest.application.rules.AssetId;
import io.geoalert.ingest.application.rules.Coordinates;
import io.geoalert.ingest.application.rules.ReportId;
import io.geoalert.ingest.application.rules.RequiredFields;
import io.geoalert.ingest.application.rules.Timestamp;
import io.geoalert.ingest.domain.rules.LineRule;
import java.util.List;

/** The standard rules for tests that build a validator without Spring. */
final class StandardRules {

    private StandardRules() {}

    static List<LineRule> list() {
        return List.of(new RequiredFields(), new Coordinates(), new Timestamp(), new AssetId(), new ReportId());
    }

    static LineValidator validator() {
        return new LineValidator(list());
    }
}
