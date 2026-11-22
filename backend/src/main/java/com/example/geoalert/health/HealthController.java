package com.example.geoalert.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness of this instance (no authentication, as the contract says). For now it only
 * says the instance can answer requests; the database check is added with the database.
 */
@RestController
public class HealthController {

    public record HealthResponse(String status) {}

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
