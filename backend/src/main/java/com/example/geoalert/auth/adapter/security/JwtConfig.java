package com.example.geoalert.auth.adapter.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Both instances share one secret, so either can check a token the other issued (HS256). */
@Configuration
class JwtConfig {

    static final String ISSUER = "geofence-alerts";
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(SecurityProperties props) {
        byte[] secret = resolveSecret(props).getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "The JWT signing secret must be at least " + MIN_SECRET_BYTES + " bytes.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    private static String resolveSecret(SecurityProperties props) {
        if (props.jwtSecretFile() != null && !props.jwtSecretFile().isBlank()) {
            try {
                return Files.readString(Path.of(props.jwtSecretFile())).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the JWT secret file.", e);
            }
        }
        if (props.jwtSecret() != null && !props.jwtSecret().isBlank()) {
            return props.jwtSecret();
        }
        throw new IllegalStateException(
                "Set app.security.jwt-secret-file or app.security.jwt-secret (deploy-time secret).");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        // the default checks (expiry, not-before) plus the issuer
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }
}
