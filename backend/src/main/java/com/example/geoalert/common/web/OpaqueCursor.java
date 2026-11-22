package com.example.geoalert.common.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * A paging cursor with a text key (for example an asset id): the key with a version prefix,
 * base64url encoded, so callers treat it as opaque and the format can change later.
 */
public final class OpaqueCursor {

    private static final String PREFIX = "v1:";

    private OpaqueCursor() {}

    public static String encode(String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((PREFIX + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the key, or null when there is no cursor (the first page). */
    public static String decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith(PREFIX)) {
                throw invalid();
            }
            return text.substring(PREFIX.length());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "cursor is not valid.");
    }
}
