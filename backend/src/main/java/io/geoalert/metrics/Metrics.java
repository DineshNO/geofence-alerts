package io.geoalert.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Counters of this instance, kept in memory and readable at GET /metrics with no external
 * service. They show rates and problems (lines received, rejected, failed batches, retries); they
 * are not the stats of the contract, which come from the database and cover both instances.
 */
@Component
public class Metrics {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String name) {
        increment(name, 1);
    }

    public void increment(String name, long amount) {
        if (amount != 0) {
            counters.computeIfAbsent(name, n -> new LongAdder()).add(amount);
        }
    }

    public long get(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0 : adder.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((name, adder) -> result.put(name, adder.sum()));
        return result;
    }
}
