package com.example.geoalert.stream.application;

import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.stream.domain.AlertEvent;
import com.example.geoalert.stream.domain.AlertEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open streams of this instance, held in memory. This is not shared state and no rule depends
 * on it: every instance gets every event from the event bus and pushes it to its own browsers.
 *
 * <p>Each client sends on its own single thread, so a slow browser never holds up the others and
 * its events keep their order. A client that cannot be written to is dropped; the console then
 * reconnects and refetches. Idle streams get a comment line so proxies keep them open.
 */
@Component
public class AlertStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(AlertStreamRegistry.class);

    private final class Client {
        final SseEmitter emitter;
        final ExecutorService sender =
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("alert-stream-send-", 0).factory());

        Client(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(SseEmitter.SseEventBuilder event) {
            sender.execute(
                    () -> {
                        try {
                            emitter.send(event);
                        } catch (IOException | RuntimeException e) {
                            drop(this);
                        }
                    });
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final StreamProperties properties;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("alert-stream-heartbeat").daemon(true).factory());

    public AlertStreamRegistry(AlertEventBus bus, StreamProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        bus.subscribe(this::onEvent);
        long period = Math.max(50, properties.heartbeat().toMillis());
        heartbeat.scheduleAtFixedRate(this::ping, period, period, TimeUnit.MILLISECONDS);
    }

    /** Opens a stream that closes by itself after {@code lifetime} (the life left of the token). */
    public SseEmitter open(Duration lifetime) {
        if (clients.size() >= properties.maxClients()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_ERROR", "Too many streams are open.");
        }
        SseEmitter emitter = new SseEmitter(Math.max(1000, lifetime.toMillis()));
        Client client = new Client(emitter);
        clients.add(client);
        emitter.onCompletion(() -> drop(client));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> drop(client));
        client.send(SseEmitter.event().name("ready").data("{\"serverTime\":\"" + Instant.now() + "\"}"));
        return emitter;
    }

    public int openStreams() {
        return clients.size();
    }

    /** An alert event, or {@code null} when events may have been missed (the console refetches). */
    private void onEvent(AlertEvent event) {
        String name = event == null ? "resync" : "alert.changed";
        String json;
        try {
            json = event == null ? "{}" : mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("An alert event could not be written and was skipped");
            return;
        }
        for (Client client : clients) {
            client.send(SseEmitter.event().name(name).data(json));
        }
    }

    private void ping() {
        for (Client client : clients) {
            client.send(SseEmitter.event().comment("ping"));
        }
    }

    private void drop(Client client) {
        if (clients.remove(client)) {
            client.sender.shutdown();
            try {
                client.emitter.complete();
            } catch (RuntimeException ignored) {
                // already finished
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        clients.forEach(this::drop);
    }
}
