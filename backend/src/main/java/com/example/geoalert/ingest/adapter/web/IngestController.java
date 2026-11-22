package com.example.geoalert.ingest.adapter.web;

import com.example.geoalert.common.web.RequestIdFilter;
import com.example.geoalert.ingest.application.BatchResult;
import com.example.geoalert.ingest.application.IngestService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class IngestController {

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * Up to 1,000 reports, one JSON document per line. A wrong content type is refused with
     * 415 by the mapping; every line is judged on its own, so the answer is 200 even when some
     * lines are rejected.
     */
    @PostMapping(path = "/batch", consumes = "application/x-ndjson")
    public BatchResult batch(HttpServletRequest request) throws IOException {
        var lines = NdjsonBody.readLines(request.getInputStream());
        return ingest.ingest(lines, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
