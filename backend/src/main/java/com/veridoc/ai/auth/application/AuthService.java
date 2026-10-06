package com.veridoc.ai.auth.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.auth.api.dto.AuthDtos.AuthResponse;
import com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RefreshRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest;
import com.veridoc.ai.common.trace.TraceContext;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.security.jwt.JwtTokenService;
import com.veridoc.ai.user.api.dto.UserResponse;
import com.veridoc.ai.user.application.UserService;
import com.veridoc.ai.user.domain.User;



/**
 * Registration, login and token refresh.
 *
 * <p>Passwords, JWTs and API keys are never logged. Only non-identifying events
 * (outcome, timing) are recorded.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String TOKEN_TYPE = "Bearer";

    private final UserService userService;
    private final JwtTokenService tokenService;

    public AuthService(UserService userService, JwtTokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = userService.register(request.email(), request.password(), request.displayName());
        log.info("Registered user id={} traceId={}", user.getId(), TraceContext.currentTraceId());
        return issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        long startNanos = System.nanoTime();
        User user = userService.authenticate(request.email(), request.password());
        log.info("Login succeeded userId={} durationMs={} traceId={}",
                user.getId(),
                (System.nanoTime() - startNanos) / 1_000_000,
                TraceContext.currentTraceId());
        return issue(user);
    }

    /**
     * Exchanges a refresh token for a new access token. The token's subject is
     * re-loaded from the database so a disabled account or a deleted user cannot
     * keep minting access tokens from a still-valid refresh token.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        AuthenticatedUser caller = tokenService.parseRefreshToken(request.refreshToken());
        User user = userService.requireById(caller.userId());
        user.requireEnabled();
        return issue(user);
    }

    private AuthResponse issue(User user) {
        JwtTokenService.TokenPair pair = tokenService.issueTokens(toPrincipal(user));
        return new AuthResponse(
                TOKEN_TYPE,
                pair.accessToken(),
                pair.refreshToken(),
                pair.accessExpiresAt().getEpochSecond() - Instant.now().getEpochSecond(),
                UserResponse.from(user));
    }

    static AuthenticatedUser toPrincipal(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }
}