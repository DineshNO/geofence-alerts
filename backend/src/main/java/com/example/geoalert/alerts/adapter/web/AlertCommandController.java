package com.example.geoalert.alerts.adapter.web;

import com.example.geoalert.alerts.application.AlertCommandService;
import com.example.geoalert.alerts.domain.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** claim, release and resolve. Who may call them is decided by the security rules. */
@RestController
@RequestMapping("/api/v1/alerts/{alertId}")
public class AlertCommandController {

    public record ResolveRequest(String note) {}

    private final AlertCommandService commands;

    public AlertCommandController(AlertCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/claim")
    public AlertResponse claim(@PathVariable String alertId, Authentication auth) {
        return AlertResponse.of(commands.claim(alertId, actor(auth)));
    }

    @PostMapping("/release")
    public AlertResponse release(@PathVariable String alertId, Authentication auth) {
        return AlertResponse.of(commands.release(alertId, actor(auth)));
    }

    @PostMapping("/resolve")
    public AlertResponse resolve(
            @PathVariable String alertId, @RequestBody ResolveRequest body, Authentication auth) {
        return AlertResponse.of(commands.resolve(alertId, actor(auth), body.note()));
    }

    private static Actor actor(Authentication auth) {
        boolean supervisor =
                auth.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_SUPERVISOR"));
        return new Actor(auth.getName(), supervisor);
    }
}
