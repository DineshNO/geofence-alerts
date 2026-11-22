package com.example.geoalert.stream.domain;

import java.util.List;
import java.util.function.Consumer;

/**
 * Port: tells every backend instance, and so every operator's browser, that an alert changed.
 *
 * <p>{@code publish} is called inside the transaction that changes the alert, and the event is
 * delivered only if that transaction commits. A subscriber gets the events of every instance,
 * including its own.
 */
public interface AlertEventBus {

    void publish(List<AlertEvent> events);

    /** The handler is called for each event, and with {@code null} when events may have been missed. */
    void subscribe(Consumer<AlertEvent> handler);
}
