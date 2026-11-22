package io.geoalert.common.web;

import org.springframework.http.HttpStatus;

/** Every list endpoint is bounded: a limit outside 1..max is refused, never silently changed. */
public final class Limits {

    private Limits() {}

    public static int check(Integer requested, int defaultLimit, int max) {
        if (requested == null) {
            return defaultLimit;
        }
        if (requested < 1 || requested > max) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "limit must be from 1 to " + max + ".");
        }
        return requested;
    }
}
