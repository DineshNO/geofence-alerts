package io.geoalert.auth.adapter.security;

import io.geoalert.common.web.ProblemJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless bearer-token security. Who may call what is decided here, in one place, by role.
 * Failures are answered as problem+json with the contract's codes.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder decoder,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(HttpMethod.GET, "/health")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/v1/reports/batch")
                                        .hasRole("INGEST")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/stats")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.GET, "/metrics")
                                        .authenticated()
                                        .requestMatchers("/api/v1/**")
                                        .hasAnyRole("OPERATOR", "SUPERVISOR")
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter()))
                                        .authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        return http.build();
    }

    /** The role claim becomes the authority ROLE_OPERATOR and so on. */
    private static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    String role = jwt.getClaimAsString(JwtTokenIssuer.ROLE_CLAIM);
                    return role == null
                            ? List.of()
                            : List.of(new SimpleGrantedAuthority("ROLE_" + role));
                });
        return converter;
    }

    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, ex) ->
                ProblemJson.write(
                        request,
                        response,
                        mapper,
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        "A valid access token is required.");
    }

    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) ->
                ProblemJson.write(
                        request,
                        response,
                        mapper,
                        HttpStatus.FORBIDDEN,
                        "FORBIDDEN",
                        "The signed-in role is not allowed to do this.");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
