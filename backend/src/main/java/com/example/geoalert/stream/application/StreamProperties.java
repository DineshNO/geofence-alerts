package com.example.geoalert.stream.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The live alert stream: how often an idle stream is pinged, and how many may be open at once. */
@ConfigurationProperties("app.stream")
public record StreamProperties(Duration heartbeat, Integer maxClients) {

    public StreamProperties {
        if (heartbeat == null) {
            heartbeat = Duration.ofSeconds(15);
        }
        if (maxClients == null || maxClients < 1) {
            maxClients = 200;
        }
    }
}
