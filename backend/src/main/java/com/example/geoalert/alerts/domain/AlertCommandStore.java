package com.example.geoalert.alerts.domain;

import java.util.Optional;

/** Port: reading and changing one alert for a command. */
public interface AlertCommandStore {

    /** Reads the alert and locks its row until the transaction ends, so commands queue up. */
    Optional<Alert> lockById(long id);

    /** OPEN to CLAIMED with this owner. Returns the alert as it is now. */
    Alert claim(long id, String username);

    /** CLAIMED to OPEN with no owner. Returns the alert as it is now. */
    Alert release(long id);

    /** CLAIMED to RESOLVED with the note. Returns the alert as it is now. */
    Alert resolve(long id, String username, String note);
}
