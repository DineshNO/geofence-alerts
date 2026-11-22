package com.example.geoalert.auth.adapter.web;

import com.example.geoalert.auth.application.AuthService;
import com.example.geoalert.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    public record LoginRequest(String username, String password) {}

    public record UserInfo(String username, String role) {}

    public record LoginResponse(
            String accessToken, String tokenType, long expiresInSec, UserInfo user) {}

    private final AuthService auth;

    public LoginController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.username() == null
                || request.username().isBlank()
                || request.password() == null
                || request.password().isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "username and password are required.");
        }
        AuthService.LoginResult result = auth.login(request.username(), request.password());
        return new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresInSec(),
                new UserInfo(result.username(), result.role().name()));
    }
}
