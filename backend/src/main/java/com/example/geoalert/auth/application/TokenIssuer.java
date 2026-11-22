package com.example.geoalert.auth.application;

import com.example.geoalert.auth.domain.User;

/** Port: creates a signed access token for a user. */
public interface TokenIssuer {

    record IssuedToken(String value, long expiresInSec) {}

    IssuedToken issue(User user);
}
