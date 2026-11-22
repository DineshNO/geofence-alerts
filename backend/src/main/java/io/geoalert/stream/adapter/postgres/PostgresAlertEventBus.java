package io.geoalert.stream.adapter.postgres;

import io.geoalert.stream.domain.AlertEvent;
import io.geoalert.stream.domain.AlertEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The event bus on PostgreSQL LISTEN and NOTIFY, so no extra component has to be run.
 *
 * <p>Publishing is a {@code pg_notify} inside the caller's transaction: PostgreSQL delivers it to
 * every listening instance only after the commit, and never for a rollback. Each instance keeps one
 * dedicated connection (not from the pool) that listens and passes each event to its subscribers.
 *
 * <p>Notifications are not stored: while the listener connection is down, events are lost. When it
 * comes back, subscribers are called with {@code null} so they can tell their clients to refetch.
 * Only low-volume events may go through NOTIFY (it takes a lock while committing): alert changes,
 * never positions.
 */
@Component
class PostgresAlertEventBus implements AlertEventBus, SmartLifecycle {

    static final String CHANNEL = "alerts";
    static final String LISTENER_APPLICATION_NAME = "geoalert-listener";

    private static final Logger log = LoggerFactory.getLogger(PostgresAlertEventBus.class);
    private static final long POLL_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = 15_000;

    private final JdbcTemplate jdbc;
    private final DataSourceProperties datasource;
    private final ObjectMapper mapper;
    private final List<Consumer<AlertEvent>> subscribers = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;

    PostgresAlertEventBus(JdbcTemplate jdbc, DataSourceProperties datasource, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.datasource = datasource;
        this.mapper = mapper;
    }

    // ---- publish (inside the caller's transaction)

    @Override
    public void publish(List<AlertEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<Object> payloads = new ArrayList<>(events.size());
        for (AlertEvent event : events) {
            try {
                payloads.add(mapper.writeValueAsString(event));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot write the alert event.", e);
            }
        }
        String values = String.join(",", java.util.Collections.nCopies(payloads.size(), "(?)"));
        jdbc.queryForList(
                "SELECT pg_notify('" + CHANNEL + "', t.payload) FROM (VALUES " + values
                        + ") AS t(payload)",
                payloads.toArray());
    }

    @Override
    public void subscribe(Consumer<AlertEvent> handler) {
        subscribers.add(handler);
    }

    // ---- listen (a dedicated connection and thread)

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("alert-listener").daemon(true).unstarted(this::listenForever);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread t = thread;
        close(connection);
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenForever() {
        long backoff = 1000;
        boolean wasDown = false;
        while (running) {
            try (Connection conn = open()) {
                connection = conn;
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                if (wasDown) {
                    log.warn("The alert listener is back; clients are told to refetch");
                    dispatch(null);
                } else {
                    log.info("The alert listener is running");
                }
                wasDown = false;
                backoff = 1000;
                PGConnection pg = conn.unwrap(PGConnection.class);
                long lastCheck = System.currentTimeMillis();
                while (running) {
                    PGNotification[] notifications = pg.getNotifications((int) POLL_MILLIS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            handle(n.getParameter());
                        }
                    }
                    if (System.currentTimeMillis() - lastCheck > 30_000) {
                        try (Statement st = conn.createStatement()) {
                            st.execute("SELECT 1"); // notices a dead connection that is quiet
                        }
                        lastCheck = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                wasDown = true;
                log.warn("The alert listener lost its connection ({}); trying again in {} ms", e.getMessage(), backoff);
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", datasource.determineUsername());
        props.setProperty("password", datasource.determinePassword());
        props.setProperty("ApplicationName", LISTENER_APPLICATION_NAME);
        return DriverManager.getConnection(datasource.determineUrl(), props);
    }

    private void handle(String payload) {
        try {
            dispatch(mapper.readValue(payload, AlertEvent.class));
        } catch (Exception e) {
            log.warn("An alert event could not be read and was skipped");
        }
    }

    private void dispatch(AlertEvent event) {
        for (Consumer<AlertEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                log.warn("An alert event subscriber failed: {}", e.getMessage());
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(Connection conn) {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException ignored) {
            // closing only to stop the listener
        }
    }
}
