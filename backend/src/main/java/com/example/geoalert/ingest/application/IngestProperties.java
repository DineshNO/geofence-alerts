package com.example.geoalert.ingest.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How often a batch is tried when the database fails in a way that may pass (a deadlock). */
@ConfigurationProperties("app.ingest")
public record IngestProperties(Integer retryAttempts, Duration retryBaseDelay) {

    public IngestProperties {
        if (retryAttempts == null || retryAttempts < 1) {
            retryAttempts = 4;
        }
        if (retryBaseDelay == null) {
            retryBaseDelay = Duration.ofMillis(50);
        }
    }
}
