package com.example.geoalert.ingest.application;

import com.example.geoalert.ingest.domain.RejectedLineQueries;
import com.example.geoalert.ingest.domain.RejectedLineQueries.StoredRejection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RejectionQueryService {

    private final RejectedLineQueries queries;

    public RejectionQueryService(RejectedLineQueries queries) {
        this.queries = queries;
    }

    public List<StoredRejection> newestFirst(long beforeId, int limit) {
        return queries.newestFirst(beforeId, limit);
    }
}
