package com.veridoc.ai.auth.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.auth.api.dto.AuthDtos.AuthResponse;
import com.veridoc.ai.auth.api.dto.AuthDtos.ForgotPasswordRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RefreshRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest;
import com.veridoc.ai.common.trace.TraceContext;
import com.veridoc.ai.config.properties.RateLimitProperties;
import com.veridoc.ai.security.RedisRateLimiter;
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
    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AuthService(UserService userService,
                       JwtTokenService tokenService,
                       RedisRateLimiter rateLimiter,
                       RateLimitProperties rateLimitProperties) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
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
        // Per-account bucket: throttles credential stuffing against one account
        // without a legitimate client hammering unrelated users' emails.
        rateLimiter.check("login", request.email(), rateLimitProperties.login());
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

    /**
     * Self-service reset: replaces the password for the account owning the
     * email. Unknown emails succeed silently (no enumeration) and pay the same
     * BCrypt cost. Throttled per email so the endpoint cannot be used to brute
     * force someone's account into a new password.
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        rateLimiter.check("forgot-password", request.email(),
                rateLimitProperties.forgotPassword());
        userService.resetPassword(request.email(), request.newPassword());
        // Deliberately no email or password in the log line.
        log.info("Password reset processed traceId={}", TraceContext.currentTraceId());
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