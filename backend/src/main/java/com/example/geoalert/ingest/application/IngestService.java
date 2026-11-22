package com.example.geoalert.ingest.application;

import com.example.geoalert.ingest.domain.BatchLog;
import com.example.geoalert.ingest.domain.LineOutcome;
import com.example.geoalert.ingest.domain.LineValidator;
import com.example.geoalert.ingest.domain.ParsedReport;
import com.example.geoalert.ingest.domain.RejectedLine;
import com.example.geoalert.ingest.domain.RejectedLineStore;
import com.example.geoalert.ingest.domain.ReportStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes in one batch. Every line is judged on its own before any database work (a bad line
 * can never affect a good one, R6); the valid lines are then written in one transaction.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Rejected lines are kept truncated, so one huge garbage line cannot fill the table. */
    static final int MAX_STORED_LINE_CHARS = 2048;

    private final LineValidator validator = new LineValidator();
    private final ReportStore reports;
    private final RejectedLineStore rejectedLines;
    private final BatchLog batchLog;
    private final TransactionTemplate tx;
    private final String instance;

    public IngestService(
            ReportStore reports,
            RejectedLineStore rejectedLines,
            BatchLog batchLog,
            TransactionTemplate tx,
            @Value("${app.instance-name:backend}") String instance) {
        this.reports = reports;
        this.rejectedLines = rejectedLines;
        this.batchLog = batchLog;
        this.tx = tx;
        this.instance = instance;
    }

    /** The non-empty lines of one request, in order. */
    public BatchResult ingest(List<String> lines, String requestId) {
        // 1. judge every line on its own: no database, no shared state
        List<ParsedReport> valid = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>();
        Set<String> seenInThisBatch = new HashSet<>();
        int replacedValues = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            LineOutcome outcome = validator.validate(lines.get(i));
            switch (outcome) {
                case LineOutcome.Valid ok -> {
                    replacedValues += ok.replacedFields().size();
                    // a report id repeated inside the batch is a duplicate of the first one
                    if (seenInThisBatch.add(ok.report().reportId())) {
                        valid.add(ok.report());
                    }
                }
                case LineOutcome.Rejected bad ->
                        rejected.add(
                                new RejectedLine(
                                        lineNo, bad.code(), bad.detail(), storedText(lines.get(i))));
            }
        }
        if (replacedValues > 0) {
            log.info("Optional values replaced with NULL: {}", replacedValues);
        }

        // 2. one transaction for the whole batch: all of it is stored, or none of it
        int received = lines.size();
        int validLines = received - rejected.size();
        return tx.execute(status -> persist(valid, rejected, received, validLines, requestId));
    }

    private BatchResult persist(
            List<ParsedReport> valid,
            List<RejectedLine> rejected,
            int received,
            int validLines,
            String requestId) {
        long batchId = batchLog.nextBatchId();

        Set<String> storedIds = valid.isEmpty() ? Set.of() : reports.insertNew(valid);
        int accepted = storedIds.size();
        int duplicates = validLines - accepted;

        if (!rejected.isEmpty()) {
            rejectedLines.saveAll(batchId, rejected);
        }
        // the batch row goes last, as the final statement before the commit
        batchLog.record(
                new BatchLog.BatchRecord(
                        batchId, requestId, instance, received, accepted, duplicates, rejected.size()));

        List<BatchResult.LineRejection> answers =
                rejected.stream()
                        .map(r -> new BatchResult.LineRejection(r.lineNo(), r.code().name(), r.detail()))
                        .toList();
        return new BatchResult(received, accepted, duplicates, rejected.size(), answers);
    }

    private static String storedText(String line) {
        String text =
                line.length() > MAX_STORED_LINE_CHARS
                        ? line.substring(0, MAX_STORED_LINE_CHARS)
                        : line;
        // the database cannot hold a NUL character
        return text.replace('\u0000', '�');
    }
}
