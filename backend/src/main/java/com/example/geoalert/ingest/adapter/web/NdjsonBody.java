package com.example.geoalert.ingest.adapter.web;

import com.example.geoalert.common.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Reads the body of a batch request: at most 5 MB and 1,000 non-empty lines. */
final class NdjsonBody {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_LINES = 1000;

    private NdjsonBody() {}

    static List<String> readLines(InputStream in) throws IOException {
        // read one byte more than allowed, so a body that is too big is noticed without
        // reading all of it
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw tooLarge("The request is larger than 5 MB.");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String line = text.substring(start, i);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                // empty (or blank) lines are not counted and are not numbered
                if (!line.isBlank()) {
                    if (lines.size() == MAX_LINES) {
                        throw tooLarge("A request holds at most " + MAX_LINES + " lines.");
                    }
                    lines.add(line);
                }
                start = i + 1;
            }
        }
        return lines;
    }

    private static ApiException tooLarge(String detail) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", detail);
    }
}
