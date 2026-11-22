package com.example.geoalert.stats.domain;

/**
 * Running totals of the whole system: every delivered batch since the database was created, both
 * instances together, kept across restarts.
 */
public record Stats(
        long received,
        long accepted,
        long duplicates,
        long rejected,
        long assets,
        AlertCounts alerts) {

    public record AlertCounts(long open, long claimed, long resolved) {}
}
