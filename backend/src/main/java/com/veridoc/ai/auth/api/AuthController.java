package com.veridoc.ai.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.veridoc.ai.auth.api.dto.AuthDtos.AuthResponse;
import com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RefreshRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest;
import com.veridoc.ai.auth.application.AuthService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.user.api.dto.UserResponse;
import com.veridoc.ai.user.application.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Thin HTTP adapter for authentication. No business logic here. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and return a token pair")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Mint a new access token from a refresh token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Return the currently authenticated user")
    public UserResponse me(@CurrentUser com.veridoc.ai.security.authenticated.AuthenticatedUser user) {
        // The user id comes from the validated token, never from the request.
        return UserResponse.from(userService.requireById(user.userId()));
    }
}