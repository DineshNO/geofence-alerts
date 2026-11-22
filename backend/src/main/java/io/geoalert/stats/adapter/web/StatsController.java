package io.geoalert.stats.adapter.web;

import io.geoalert.stats.application.StatsService;
import io.geoalert.stats.domain.Stats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public Stats stats() {
        return stats.stats();
    }
}
