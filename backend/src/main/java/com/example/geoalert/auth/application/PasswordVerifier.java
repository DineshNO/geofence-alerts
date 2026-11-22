package com.example.geoalert.auth.application;

/** Port: checks a password against a stored hash. */
public interface PasswordVerifier {

    boolean matches(String rawPassword, String passwordHash);

    /**
     * Does the same work as {@link #matches} against a fixed hash and always answers false.
     * Used for an unknown user so that a wrong user name takes as long as a wrong password.
     */
    void burnTime(String rawPassword);
}
