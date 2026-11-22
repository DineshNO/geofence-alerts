package io.geoalert.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * For slice tests that look at one controller or filter and not at security: lets every
 * request through. The real rules are tested in the authentication tests.
 */
@TestConfiguration
public class PermitAllSecurityTestConfig {

    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
