package com.example.geoalert.auth.adapter.jdbc;

import com.example.geoalert.auth.domain.Role;
import com.example.geoalert.auth.domain.User;
import com.example.geoalert.auth.domain.UserStore;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserStore implements UserStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> find(String username) {
        return jdbc
                .query(
                        "SELECT username, role, password_hash FROM users WHERE username = :username",
                        Map.of("username", username),
                        (rs, row) ->
                                new User(
                                        rs.getString("username"),
                                        Role.valueOf(rs.getString("role")),
                                        rs.getString("password_hash")))
                .stream()
                .findFirst();
    }

    @Override
    public void upsert(User user) {
        jdbc.update(
                "INSERT INTO users (username, role, password_hash)"
                        + " VALUES (:username, :role, :hash)"
                        + " ON CONFLICT (username) DO UPDATE"
                        + " SET role = EXCLUDED.role, password_hash = EXCLUDED.password_hash",
                Map.of(
                        "username", user.username(),
                        "role", user.role().name(),
                        "hash", user.passwordHash()));
    }
}
