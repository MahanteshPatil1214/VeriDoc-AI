package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate limits expressed as {@code permits/period}, e.g. {@code 10/PT1M}.
 * Disabled wholesale when {@code enabled} is false (used by tests).
 */
@ConfigurationProperties(prefix = "veridoc.rate-limit")
public record RateLimitProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue("10/PT1M") String login,

        @DefaultValue("20/PT1H") String upload,

        @DefaultValue("60/PT1H") String chat,

        @DefaultValue("30/PT1H") String processing
) {
    public RateLimitProperties {
        if (enabled) {
            require(login, "login");
            require(upload, "upload");
            require(chat, "chat");
            require(processing, "processing");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("veridoc.rate-limit." + name + " must be configured");
        }
    }
}