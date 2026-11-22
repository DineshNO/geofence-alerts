package io.geoalert.auth.adapter.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings. The signing secret comes from the environment or from a mounted file
 * (the file wins); it is never in the repository, an image or the logs.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(String jwtSecret, String jwtSecretFile, Duration tokenTtl) {

    public SecurityProperties {
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(30);
        }
    }
}
