package com.example.geoalert.positions.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port: the current-position table, one row per asset. */
public interface AssetCurrentStore {

    /**
     * Creates the rows of assets that have no row yet and returns the ids that were created.
     * A concurrent insert of the same asset makes this one skip it, so exactly one writer sees
     * an asset as new.
     */
    Set<String> insertMissing(List<AssetState> states);

    /** Locks the rows of existing assets, in asset id order, and returns their state. */
    Map<String, AssetState> lockExisting(Collection<String> assetIds);

    /** Moves positions forward. A row is only updated when the new report is newer (R2). */
    void update(List<AssetState> states);
}
