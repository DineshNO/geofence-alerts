package io.geoalert.assets.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a report came from (CR-1). The type is derived from the {@code source} text of the
 * report by its prefix: SAT-2 is a satellite, GND-7 a ground station, MOB-1 a mobile unit.
 * Anything else, including no source at all, is UNKNOWN.
 *
 * <p>The same rule exists in SQL ({@link #SQL_DERIVE}). It is used to filter old rows that
 * have no stored type yet, and by the backfill. A parity test keeps the two identical.
 */
public enum SourceType {
    SATELLITE("SAT"),
    GROUND("GND"),
    MOBILE("MOB"),
    UNKNOWN(null);

    /** The rule in SQL, for a column named {@code source}. */
    public static final String SQL_DERIVE =
            "CASE WHEN source LIKE 'SAT%' THEN 'SATELLITE'"
                    + " WHEN source LIKE 'GND%' THEN 'GROUND'"
                    + " WHEN source LIKE 'MOB%' THEN 'MOBILE'"
                    + " ELSE 'UNKNOWN' END";

    private final String prefix;

    SourceType(String prefix) {
        this.prefix = prefix;
    }

    public static SourceType derive(String source) {
        if (source != null) {
            for (SourceType type : values()) {
                if (type.prefix != null && source.startsWith(type.prefix)) {
                    return type;
                }
            }
        }
        return UNKNOWN;
    }

    /** Parses a query value such as "SATELLITE" (exact, upper case). */
    public static Optional<SourceType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
