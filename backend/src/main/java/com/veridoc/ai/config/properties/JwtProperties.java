package com.veridoc.ai.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT configuration. The signing secret is supplied exclusively through the
 * environment ({@code JWT_SECRET}) and is never committed.
 */
@ConfigurationProperties(prefix = "veridoc.jwt")
public record JwtProperties(

        @DefaultValue("") String secret,

        @DefaultValue("veridoc-ai") String issuer,

        @DefaultValue("PT1H") Duration accessTokenTtl,

        @DefaultValue("P30D") Duration refreshTokenTtl
) {
    public JwtProperties {
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalStateException("veridoc.jwt.access-token-ttl must be positive");
        }
        if (refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalStateException("veridoc.jwt.refresh-token-ttl must exceed access-token-ttl");
        }
    }

    /** Minimum accepted HS256 secret length in bytes. */
    public static final int MIN_SECRET_BYTES = 32;
}