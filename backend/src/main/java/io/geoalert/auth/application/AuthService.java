package io.geoalert.auth.application;

import io.geoalert.auth.domain.Role;
import io.geoalert.auth.domain.User;
import io.geoalert.auth.domain.UserStore;
import io.geoalert.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Login: a user name and password become a signed token. No database work needs a transaction. */
@Service
public class AuthService {

    public record LoginResult(String accessToken, long expiresInSec, String username, Role role) {}

    private final UserStore users;
    private final PasswordVerifier passwords;
    private final TokenIssuer tokens;

    public AuthService(UserStore users, PasswordVerifier passwords, TokenIssuer tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    public LoginResult login(String username, String password) {
        User user = users.find(username).orElse(null);
        if (user == null) {
            passwords.burnTime(password);
            throw invalidCredentials();
        }
        if (!passwords.matches(password, user.passwordHash())) {
            throw invalidCredentials();
        }
        TokenIssuer.IssuedToken token = tokens.issue(user);
        return new LoginResult(token.value(), token.expiresInSec(), user.username(), user.role());
    }

    /** The same answer for an unknown user and a wrong password, so users cannot be guessed. */
    private static ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "The user name or password is wrong.");
    }
}
