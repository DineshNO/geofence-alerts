package io.geoalert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Applies the database changesets and exits. It is the one-shot "migrate" step of an install or an
 * upgrade, run from the same image as the backend:
 *
 * <pre>java -cp app.jar -Dloader.main=io.geoalert.MigrateMain
 *     org.springframework.boot.loader.launch.PropertiesLauncher</pre>
 *
 * It starts no web server and needs only the database address and password, not the signing secret
 * or the users file. The backends run with their own Liquibase switched off, so each step of the
 * upgrade (the schema first, then one instance at a time) is a separate, deliberate command.
 *
 * <p>Settings come from the environment: SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and the
 * password from the file named by SPRING_DATASOURCE_PASSWORD_FILE (or SPRING_DATASOURCE_PASSWORD).
 */
public final class MigrateMain {

    private MigrateMain() {}

    public static void main(String[] args) throws Exception {
        String url = required("SPRING_DATASOURCE_URL");
        String user = required("SPRING_DATASOURCE_USERNAME");
        migrate(url, user, password());
        System.out.println("The database is up to date.");
    }

    /** Applies every changeset that has not been applied yet. Safe to run again. */
    public static void migrate(String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Database database =
                    DatabaseFactory.getInstance()
                            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase =
                    new Liquibase(
                            "db/changelog/db.changelog-master.yaml",
                            new ClassLoaderResourceAccessor(),
                            database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static String password() throws Exception {
        String file = System.getenv("SPRING_DATASOURCE_PASSWORD_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        }
        return required("SPRING_DATASOURCE_PASSWORD");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set.");
        }
        return value;
    }
}
