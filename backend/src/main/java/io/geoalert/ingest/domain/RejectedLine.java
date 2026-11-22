package io.geoalert.ingest.domain;

/** A line that was rejected: its position among the non-empty lines, the reason and the text. */
public record RejectedLine(int lineNo, RejectionCode code, String detail, String rawLine) {}
