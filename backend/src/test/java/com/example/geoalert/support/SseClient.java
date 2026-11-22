package com.example.geoalert.support;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test helper: reads a Server-Sent Events stream over real HTTP, as the console does with fetch.
 * It sends the Authorization header, which the browser's EventSource could not.
 */
public final class SseClient implements AutoCloseable {

    public record Event(String name, String data) {}

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final AtomicInteger comments = new AtomicInteger();
    private final CountDownLatch ended = new CountDownLatch(1);
    private final int status;
    private final InputStream body;

    private SseClient(int status, InputStream body) {
        this.status = status;
        this.body = body;
    }

    public static SseClient open(String url, String bearer) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", bearer)
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        SseClient client = new SseClient(response.statusCode(), response.body());
        Thread.ofVirtual().start(client::read);
        return client;
    }

    public int status() {
        return status;
    }

    private void read() {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String name = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        events.add(new Event(name, data.toString()));
                    }
                    name = "message";
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    comments.incrementAndGet();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        } catch (Exception ignored) {
            // the stream was closed by either side
        } finally {
            ended.countDown();
        }
    }

    /** The next event, or null if none arrives in time. */
    public Event next(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits for an event with this name, skipping others; null if it does not arrive in time. */
    public Event awaitEvent(String name, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Event event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.name().equals(name)) {
                return event;
            }
        }
        return null;
    }

    public int comments() {
        return comments.get();
    }

    public boolean awaitEnd(Duration timeout) throws InterruptedException {
        return ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            body.close();
        } catch (Exception ignored) {
            // already closed
        }
    }
}
