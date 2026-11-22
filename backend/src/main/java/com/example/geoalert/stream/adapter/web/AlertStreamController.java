package com.example.geoalert.stream.adapter.web;

import com.example.geoalert.stream.application.AlertStreamRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Added to the fixed contract: the live stream of alert changes (Server-Sent Events). The caller
 * proves who they are with the normal Authorization header, checked before the stream starts. The
 * stream ends when the token expires; the console then asks the user to sign in again.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertStreamController {

    private final AlertStreamRegistry streams;

    public AlertStreamController(AlertStreamRegistry streams) {
        this.streams = streams;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(Authentication authentication) {
        Duration lifetime = Duration.ofHours(1);
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().getExpiresAt() != null) {
            lifetime = Duration.between(Instant.now(), jwt.getToken().getExpiresAt());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no") // tells a proxy not to hold the events back
                .body(streams.open(lifetime));
    }
}
