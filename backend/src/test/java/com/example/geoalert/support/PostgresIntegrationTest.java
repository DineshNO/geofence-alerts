package com.example.geoalert.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need the real database. One PostgreSQL container, the same pinned
 * image as the deployment, is started once per test run and shared by every test class.
 *
 * <p>The users, passwords and signing secret below are values made up for tests only. Real
 * ones are supplied at deploy time and are never in the repository.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    public static final String POSTGRES_IMAGE = "postgres:16.16-alpine";

    public static final String FEED_USER = "feed";
    public static final String OPERATOR_USER = "op01";
    public static final String OPERATOR2_USER = "op02";
    public static final String SUPERVISOR_USER = "sup01";
    public static final String TEST_PASSWORD = "test-password-only";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static final Path USERS_FILE = writeUsersFile();

    static {
        POSTGRES.start();
    }

    private static Path writeUsersFile() {
        try {
            Path file = Files.createTempFile("test-users", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(
                    file,
                    """
                    [
                      {"username": "feed",  "role": "INGEST",     "password": "test-password-only"},
                      {"username": "op01",  "role": "OPERATOR",   "password": "test-password-only"},
                      {"username": "op02",  "role": "OPERATOR",   "password": "test-password-only"},
                      {"username": "sup01", "role": "SUPERVISOR", "password": "test-password-only"}
                    ]
                    """);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.security.jwt-secret", () -> "test-only-signing-secret-0123456789abcdef");
        registry.add("app.users.file", () -> USERS_FILE.toString());
    }
}
