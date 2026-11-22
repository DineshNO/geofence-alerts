package com.example.geoalert.auth.domain;

/** A user as stored: only the password hash is ever kept. */
public record User(String username, Role role, String passwordHash) {}
