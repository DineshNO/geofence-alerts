package com.example.geoalert.alerts.domain;

import java.util.List;

/** Port: where alerts are kept. */
public interface AlertStore {

    /** An alert that was really created, with its id. */
    record Created(long id, NewAlert alert) {}

    /**
     * Creates the alerts that are due and returns the ones that were really created.
     *
     * <p>R3: for one asset and one zone there is never more than one alert that is OPEN or
     * CLAIMED. A partial unique index in the database enforces it, and the insert skips a
     * conflict instead of failing, so entering a zone again while an alert is active raises
     * nothing, on either instance.
     */
    List<Created> insertNew(List<NewAlert> alerts);
}
