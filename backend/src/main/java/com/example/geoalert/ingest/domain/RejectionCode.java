package com.example.geoalert.ingest.domain;

/** The rejection codes of the contract, one per rejected line. */
public enum RejectionCode {
    MALFORMED_JSON,
    MISSING_FIELD,
    INVALID_COORDINATE,
    INVALID_TIMESTAMP,
    INVALID_FIELD
}
