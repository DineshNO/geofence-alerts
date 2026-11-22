package io.geoalert.ingest.application;

import io.geoalert.ingest.domain.LineValidator;
import io.geoalert.ingest.domain.rules.LineRule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gives the validator every {@link LineRule} bean; it sorts them by priority itself. */
@Configuration
class IngestConfig {

    @Bean
    LineValidator lineValidator(List<LineRule> rules) {
        return new LineValidator(rules);
    }
}
