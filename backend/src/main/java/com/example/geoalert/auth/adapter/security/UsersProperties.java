package com.example.geoalert.auth.adapter.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Path of the mounted users file that is read at start-up (a deploy-time secret). */
@ConfigurationProperties("app.users")
public record UsersProperties(String file) {}
