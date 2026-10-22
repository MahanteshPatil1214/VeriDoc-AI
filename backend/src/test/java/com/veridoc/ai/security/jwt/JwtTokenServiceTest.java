package com.veridoc.ai.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.veridoc.ai.config.properties.JwtProperties;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.user.domain.Role;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtTokenServiceTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtTokenService tokenService = new JwtTokenService(
            new JwtProperties(SECRET, "veridoc-ai", Duration.ofHours(1), Duration.ofDays(30)));

    @Test
    @DisplayName("issued access and refresh tokens carry the current token version")
    void issuedTokensCarryTokenVersion() {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), "ada@example.com", Role.USER, 7L);

        JwtTokenService.TokenPair tokens = tokenService.issueTokens(user);

        assertThat(tokenService.parseAccessToken(tokens.accessToken()).tokenVersion())
                .isEqualTo(7L);
        assertThat(tokenService.parseRefreshToken(tokens.refreshToken()).tokenVersion())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("a refresh token without a version claim defaults to version zero")
    void legacyRefreshTokenDefaultsToVersionZero() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        String legacyToken = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer("veridoc-ai")
                .subject("ada@example.com")
                .claim("uid", userId.toString())
                .claim("rol", Role.USER.name())
                .claim("typ", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        AuthenticatedUser parsed = tokenService.parseRefreshToken(legacyToken);

        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.tokenVersion()).isZero();
    }
}
