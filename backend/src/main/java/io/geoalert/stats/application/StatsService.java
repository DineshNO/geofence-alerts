package io.geoalert.stats.application;

import io.geoalert.stats.domain.Stats;
import io.geoalert.stats.domain.StatsQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the totals in one read-only transaction, so the numbers belong together. */
@Service
public class StatsService {

    private final StatsQueries queries;
    private final TransactionTemplate readOnly;

    public StatsService(StatsQueries queries, PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public Stats stats() {
        return readOnly.execute(status -> queries.read());
    }
}
