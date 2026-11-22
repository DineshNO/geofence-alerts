package com.example.geoalert.auth.adapter.security;

import com.example.geoalert.auth.application.TokenIssuer;
import com.example.geoalert.auth.domain.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
class JwtTokenIssuer implements TokenIssuer {

    static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final Clock clock;

    JwtTokenIssuer(JwtEncoder encoder, SecurityProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        // the payload is readable by anyone, so it carries only the user name and the role
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(JwtConfig.ISSUER)
                        .subject(user.username())
                        .issuedAt(now)
                        .expiresAt(now.plus(props.tokenTtl()))
                        .claim(ROLE_CLAIM, user.role().name())
                        .build();
        String token =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        return new IssuedToken(token, props.tokenTtl().toSeconds());
    }
}
