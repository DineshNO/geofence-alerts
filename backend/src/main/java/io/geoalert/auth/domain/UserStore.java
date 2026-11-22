package io.geoalert.auth.domain;

import java.util.Optional;

/** Port: where users are kept. */
public interface UserStore {

    Optional<User> find(String username);

    /** Creates the user or updates its role and password hash. Safe to repeat. */
    void upsert(User user);
}
