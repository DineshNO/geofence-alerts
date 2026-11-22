package com.example.geoalert.auth.adapter.security;

import com.example.geoalert.auth.domain.Role;
import com.example.geoalert.auth.domain.User;
import com.example.geoalert.auth.domain.UserStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the users from the mounted file at start-up. Passwords are hashed here and never
 * logged; the log only says how many users were seeded. A missing or invalid file stops the
 * start, so a half-configured instance never serves requests.
 */
@Component
class UsersSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsersSeeder.class);

    record SeedUser(String username, String role, String password) {}

    private final UsersProperties props;
    private final UserStore users;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    UsersSeeder(
            UsersProperties props, UserStore users, PasswordEncoder encoder, ObjectMapper mapper) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (props.file() == null || props.file().isBlank()) {
            log.info("No users file configured; no users were seeded");
            return;
        }
        List<SeedUser> seeds =
                mapper.readValue(
                        Path.of(props.file()).toFile(),
                        mapper.getTypeFactory().constructCollectionType(List.class, SeedUser.class));
        for (SeedUser seed : seeds) {
            if (blank(seed.username()) || blank(seed.password()) || blank(seed.role())) {
                throw new IllegalStateException("Every user needs a username, role and password.");
            }
            users.upsert(
                    new User(
                            seed.username(),
                            Role.valueOf(seed.role()),
                            encoder.encode(seed.password())));
        }
        log.info("Seeded {} users", seeds.size());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
