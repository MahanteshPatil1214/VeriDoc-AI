package com.veridoc.ai.security.jwt;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.user.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates HS256 access/refresh tokens.
 * The signing key never leaves the backend.
 */
@Component
public class JwtTokenService {

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLE = "rol";

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final String issuer;
    private final java.time.Duration accessTtl;
    private final java.time.Duration refreshTtl;

    public JwtTokenService(com.veridoc.ai.config.properties.JwtProperties properties) {
        this.issuer = properties.issuer();
        this.accessTtl = properties.accessTokenTtl();
        this.refreshTtl = properties.refreshTokenTtl();
        this.key = resolveKey(properties.secret());
    }

    public record TokenPair(String accessToken, String refreshToken,
                            Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    public TokenPair issueTokens(AuthenticatedUser user) {
        Instant now = Instant.now();
        return new TokenPair(
                build(user, TYPE_ACCESS, now, accessTtl),
                build(user, TYPE_REFRESH, now, refreshTtl),
                now.plus(accessTtl),
                now.plus(refreshTtl));
    }

    public String issueAccessToken(AuthenticatedUser user) {
        return build(user, TYPE_ACCESS, Instant.now(), accessTtl);
    }

    private String build(AuthenticatedUser user, String type, Instant now, java.time.Duration ttl) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(user.email())
                .claim(CLAIM_USER_ID, user.userId().toString())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Validates an access token and materialises the caller. */
    public AuthenticatedUser parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    public AuthenticatedUser parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private AuthenticatedUser parse(String token, String expectedType) {
        Claims claims = parseClaims(token);
        Object type = claims.get(CLAIM_TYPE);
        if (!expectedType.equals(type)) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Invalid token type.");
        }
        UUID userId;
        try {
            userId = UUID.fromString(claims.get(CLAIM_USER_ID, String.class));
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Invalid token subject.");
        }
        Role role;
        try {
            role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
        } catch (RuntimeException e) {
            role = Role.USER;
        }
        return new AuthenticatedUser(userId, claims.getSubject(), role);
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Token has expired.");
        } catch (JwtException | IllegalArgumentException e) {
            // Signature/format problems are indistinguishable to the caller on purpose.
            throw new AppException(ErrorCode.UNAUTHORIZED, "Invalid authentication token.");
        }
    }

    private static SecretKey resolveKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not configured. Refusing to start with an insecure default.");
        }
        byte[] bytes = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length < com.veridoc.ai.config.properties.JwtProperties.MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least "
                            + com.veridoc.ai.config.properties.JwtProperties.MIN_SECRET_BYTES
                            + " bytes.");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}