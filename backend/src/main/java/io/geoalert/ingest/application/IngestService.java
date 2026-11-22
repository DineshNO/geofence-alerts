package io.geoalert.ingest.application;

import io.geoalert.alerts.application.AlertOpening;
import io.geoalert.common.jdbc.DatabaseErrors;
import io.geoalert.ingest.domain.BatchLog;
import io.geoalert.ingest.domain.LineOutcome;
import io.geoalert.ingest.domain.LineValidator;
import io.geoalert.ingest.domain.ParsedReport;
import io.geoalert.ingest.domain.RejectedLine;
import io.geoalert.ingest.domain.RejectedLineStore;
import io.geoalert.ingest.domain.ReportStore;
import io.geoalert.metrics.Metrics;
import io.geoalert.positions.application.PositionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes in one batch.
 *
 * <p>Every line is judged on its own before any database work, so a bad line can never affect a
 * good one (R6): rejected lines are collected and the rest carry on. The valid lines are written
 * in one transaction: all of the batch is stored, or none of it.
 *
 * <p>A database failure fails the whole batch (a 500, nothing stored). Only a failure that may
 * pass (a deadlock, a lost connection) is retried, a few times with a growing delay; a schema or
 * data error is never retried. Delivering the batch again is always safe (R1). Every batch that
 * finally fails is raised as an operational alert: an ERROR log line and a counter.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Rejected lines are kept truncated, so one huge garbage line cannot fill the table. */
    static final int MAX_STORED_LINE_CHARS = 2048;

    private final LineValidator validator;
    private final ReportStore reports;
    private final PositionService positions;
    private final AlertOpening alerts;
    private final RejectedLineStore rejectedLines;
    private final BatchLog batchLog;
    private final TransactionTemplate tx;
    private final Metrics metrics;
    private final IngestProperties properties;
    private final String instance;

    public IngestService(
            LineValidator validator,
            ReportStore reports,
            PositionService positions,
            AlertOpening alerts,
            RejectedLineStore rejectedLines,
            BatchLog batchLog,
            TransactionTemplate tx,
            Metrics metrics,
            IngestProperties properties,
            @Value("${app.instance-name:backend}") String instance) {
        this.validator = validator;
        this.reports = reports;
        this.positions = positions;
        this.alerts = alerts;
        this.rejectedLines = rejectedLines;
        this.batchLog = batchLog;
        this.tx = tx;
        this.metrics = metrics;
        this.properties = properties;
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

        // 2. one transaction for the whole batch, tried again only when the failure may pass
        int received = lines.size();
        int validLines = received - rejected.size();
        BatchResult result =
                writeWithRetry(valid, rejected, received, validLines, requestId);

        metrics.increment("ingest_batches_total");
        metrics.increment("ingest_lines_received_total", result.received());
        metrics.increment("ingest_lines_accepted_total", result.accepted());
        metrics.increment("ingest_lines_duplicate_total", result.duplicates());
        metrics.increment("ingest_lines_rejected_total", result.rejected());
        metrics.increment("ingest_optional_values_replaced_total", replacedValues);
        if (replacedValues > 0) {
            log.info("Optional values replaced with NULL: {}", replacedValues);
        }
        return result;
    }

    private BatchResult writeWithRetry(
            List<ParsedReport> valid,
            List<RejectedLine> rejected,
            int received,
            int validLines,
            String requestId) {
        int maxAttempts = properties.retryAttempts();
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(
                        status -> persist(valid, rejected, received, validLines, requestId));
            } catch (RuntimeException failure) {
                if (DatabaseErrors.isTransient(failure) && attempt < maxAttempts) {
                    metrics.increment("ingest_transient_retries_total");
                    pause(attempt);
                    continue;
                }
                raiseFailedBatchAlert(failure, received, attempt, requestId);
                throw failure;
            }
        }
    }

    /**
     * The operational alert for a batch that failed for good: one ERROR line with everything
     * needed to find it (the request id is in every log line of the request), and a counter.
     */
    private void raiseFailedBatchAlert(
            RuntimeException failure, int lines, int attempts, String requestId) {
        metrics.increment("ingest_batch_failures_total");
        log.error(
                "INGEST BATCH FAILED requestId={} sqlState={} lines={} attempts={} transient={}",
                requestId,
                DatabaseErrors.sqlState(failure),
                lines,
                attempts,
                DatabaseErrors.isTransient(failure),
                failure);
    }

    /** A short growing delay with some random variation, so retrying batches do not line up. */
    private void pause(int attempt) {
        long base = properties.retryBaseDelay().toMillis();
        long delay = base * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 2 + 1));
        try {
            Thread.sleep(delay + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

        // only reports that were really new move positions; a duplicate changes nothing (R1)
        List<ParsedReport> newReports =
                valid.stream().filter(r -> storedIds.contains(r.reportId())).toList();
        alerts.open(positions.apply(newReports));

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
