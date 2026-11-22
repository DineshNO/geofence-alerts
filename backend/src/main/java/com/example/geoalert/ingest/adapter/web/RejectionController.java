package com.example.geoalert.ingest.adapter.web;

import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.common.web.Limits;
import com.example.geoalert.common.web.OpaqueCursor;
import com.example.geoalert.ingest.application.RejectionQueryService;
import com.example.geoalert.ingest.domain.RejectedLineQueries.StoredRejection;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Added to the fixed contract: every rejected line can be inspected later (R6), newest first. */
@RestController
@RequestMapping("/api/v1/rejections")
public class RejectionController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    public record RejectionItem(
            String id,
            String batchId,
            int line,
            String code,
            String detail,
            String rawLine,
            Instant rejectedAt) {}

    public record RejectionPage(List<RejectionItem> items, String nextCursor) {}

    private final RejectionQueryService rejections;

    public RejectionController(RejectionQueryService rejections) {
        this.rejections = rejections;
    }

    @GetMapping
    public RejectionPage list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        int max = Limits.check(limit, DEFAULT_LIMIT, MAX_LIMIT);
        long before = cursorId(cursor);
        List<StoredRejection> rows = rejections.newestFirst(before, max);
        boolean more = rows.size() > max;
        List<StoredRejection> page = more ? rows.subList(0, max) : rows;
        String next = more ? OpaqueCursor.encode(Long.toString(page.get(page.size() - 1).id())) : null;
        return new RejectionPage(page.stream().map(RejectionController::item).toList(), next);
    }

    private static long cursorId(String cursor) {
        String key = OpaqueCursor.decode(cursor);
        if (key == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "cursor is not valid.");
        }
    }

    private static RejectionItem item(StoredRejection r) {
        return new RejectionItem(
                Long.toString(r.id()),
                Long.toString(r.batchId()),
                r.lineNo(),
                r.code(),
                r.detail(),
                r.rawLine(),
                r.createdAt());
    }
}
