package com.example.geoalert.metrics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final Metrics metrics;

    public MetricsController(Metrics metrics) {
        this.metrics = metrics;
    }

    /** One "name value" per line, in plain text; any signed-in role may read it. */
    @GetMapping(path = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics() {
        StringBuilder out = new StringBuilder();
        metrics.snapshot().forEach((name, value) -> out.append(name).append(' ').append(value).append('\n'));
        return out.toString();
    }
}
