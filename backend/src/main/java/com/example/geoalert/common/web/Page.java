package com.example.geoalert.common.web;

import java.util.List;

/** A bounded list; {@code truncated} says more matched than the limit allowed. */
public record Page<T>(List<T> items, boolean truncated) {

    /** From a query that asked for one row more than the limit. */
    public static <T> Page<T> of(List<T> rows, int limit) {
        boolean truncated = rows.size() > limit;
        return new Page<>(truncated ? rows.subList(0, limit) : rows, truncated);
    }
}
