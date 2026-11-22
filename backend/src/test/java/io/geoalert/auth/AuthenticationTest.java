package io.geoalert.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.geoalert.auth.domain.UserStore;
import io.geoalert.support.PostgresIntegrationTest;
import io.geoalert.support.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthenticationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserStore users;
    @Autowired private SecretKey jwtSigningKey;

    private String loginBody(String user, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("username", user, "password", password));
    }

    @Test
    void loginReturnsATokenAndTheUser() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(OPERATOR_USER, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSec").value(1800))
                .andExpect(jsonPath("$.user.username").value(OPERATOR_USER))
                .andExpect(jsonPath("$.user.role").value("OPERATOR"));
    }

    @Test
    void aWrongPasswordAndAnUnknownUserGetTheSameAnswer() throws Exception {
        var wrongPassword =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody(OPERATOR_USER, "wrong")))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var unknownUser =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("nobody", "wrong")))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // same body apart from the request path details: nothing reveals which part was wrong
        assertThat(mapper.readTree(unknownUser).get("detail"))
                .isEqualTo(mapper.readTree(wrongPassword).get("detail"));
    }

    @Test
    void loginNeedsAUserNameAndAPassword() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"op01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void thePasswordIsStoredOnlyAsAHash() {
        String hash = users.find(OPERATOR_USER).orElseThrow().passwordHash();
        assertThat(hash).doesNotContain(TEST_PASSWORD).startsWith("$2");
    }

    @Test
    void aProtectedEndpointWithoutATokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aBadTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("geofence-alerts")
                        .subject(OPERATOR_USER)
                        .issuedAt(past.minusSeconds(60))
                        .expiresAt(past)
                        .claim("role", "OPERATOR")
                        .build();
        String expired =
                new NimbusJwtEncoder(
                                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(jwtSigningKey))
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        mvc.perform(get("/api/v1/alerts").header("Authorization", Tokens.bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        SecretKey other =
                new javax.crypto.spec.SecretKeySpec(
                        "another-secret-another-secret-123456".getBytes(), "HmacSHA256");
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("geofence-alerts")
                        .subject(SUPERVISOR_USER)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .claim("role", "SUPERVISOR")
                        .build();
        String forged =
                new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(other))
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        mvc.perform(get("/api/v1/alerts").header("Authorization", Tokens.bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theFeedRoleCannotReadAlertsButAnOperatorPassesTheSecurityCheck() throws Exception {
        String feed = Tokens.login(mvc, mapper, FEED_USER);
        mvc.perform(get("/api/v1/alerts").header("Authorization", Tokens.bearer(feed)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String operator = Tokens.login(mvc, mapper, OPERATOR_USER);
        // security lets the operator through; the alerts endpoint itself comes in a later step
        int statusCode =
                mvc.perform(get("/api/v1/alerts").header("Authorization", Tokens.bearer(operator)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(statusCode).isNotIn(401, 403);
    }

    @Test
    void onlyTheFeedRoleMayDeliverReports() throws Exception {
        String operator = Tokens.login(mvc, mapper, OPERATOR_USER);
        mvc.perform(
                        post("/api/v1/reports/batch")
                                .header("Authorization", Tokens.bearer(operator))
                                .contentType("application/x-ndjson")
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void healthNeedsNoToken() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
