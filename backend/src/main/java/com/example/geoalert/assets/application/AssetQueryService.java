package com.example.geoalert.assets.application;

import com.example.geoalert.assets.domain.AssetPosition;
import com.example.geoalert.assets.domain.AssetQueries;
import com.example.geoalert.assets.domain.BoundingBox;
import com.example.geoalert.assets.domain.TrackPoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Reads current positions and tracks. Each call is one SELECT, so it needs no transaction. */
@Service
public class AssetQueryService {

    private final AssetQueries queries;

    public AssetQueryService(AssetQueries queries) {
        this.queries = queries;
    }

    public List<AssetPosition> inBox(BoundingBox box, int limit) {
        return queries.inBox(box, limit);
    }

    public Optional<AssetPosition> find(String assetId) {
        return queries.find(assetId);
    }

    public List<TrackPoint> track(String assetId, Instant from, Instant to, int limit) {
        return queries.track(assetId, from, to, limit);
    }
}
