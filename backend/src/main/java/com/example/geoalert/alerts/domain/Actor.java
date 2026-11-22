package com.example.geoalert.alerts.domain;

/** Who is giving a command: the signed-in user, and whether they are a supervisor. */
public record Actor(String username, boolean supervisor) {}
