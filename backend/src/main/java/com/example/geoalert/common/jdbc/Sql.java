package com.example.geoalert.common.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small helpers shared by the JDBC repositories. */
public final class Sql {

    private Sql() {}

    /** The driver binds an OffsetDateTime to timestamptz; time is UTC everywhere. */
    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** "(?,?,?)" repeated for a multi-row VALUES list. */
    public static String valueRows(int rows, int columns) {
        String row = "(" + "?,".repeat(columns - 1) + "?)";
        return String.join(",", java.util.Collections.nCopies(rows, row));
    }
}
