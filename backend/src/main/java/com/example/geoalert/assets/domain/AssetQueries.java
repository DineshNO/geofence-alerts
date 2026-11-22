package com.example.geoalert.assets.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port: reading current positions and tracks. */
public interface AssetQueries {

    /** Assets inside the box, ordered by asset id, at most {@code limit + 1} rows. */
    List<AssetPosition> inBox(BoundingBox box, int limit);

    Optional<AssetPosition> find(String assetId);

    /**
     * Stored reports of one asset, oldest first, optionally between two instants (inclusive),
     * at most {@code limit + 1} rows.
     */
    List<TrackPoint> track(String assetId, Instant from, Instant to, int limit);
}
