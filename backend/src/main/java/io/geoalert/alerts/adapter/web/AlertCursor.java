package io.geoalert.alerts.adapter.web;

import io.geoalert.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * The paging cursor: the id of the last alert of the previous page, with a version prefix and
 * base64url encoding, so callers treat it as opaque and the format can change later.
 */
final class AlertCursor {

    private static final String PREFIX = "v1:";

    private AlertCursor() {}

    static String encode(long lastId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((PREFIX + lastId).getBytes(StandardCharsets.UTF_8));
    }

    /** No cursor means the first page. */
    static long decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith(PREFIX)) {
                throw invalid();
            }
            long id = Long.parseLong(text.substring(PREFIX.length()));
            if (id < 0) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "cursor is not valid.");
    }
}
