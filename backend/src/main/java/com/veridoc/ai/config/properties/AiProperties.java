package com.veridoc.ai.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM/embedding client behaviour. Retries are always bounded and use
 * exponential backoff; {@code maxAttempts} counts the initial call.
 */
@ConfigurationProperties(prefix = "veridoc.ai")
public record AiProperties(

        @DefaultValue("gemini-3.5-flash") String chatModel,

        @DefaultValue("gemini-embedding-001") String embeddingModel,

        @DefaultValue("3") int maxAttempts,

        @DefaultValue("PT1S") Duration initialBackoff,

        @DefaultValue("PT20S") Duration maxBackoff,

        /**
         * Hard ceiling on a single Gemini HTTP request. The google-genai SDK
         * defaults its connect/read/write timeouts to zero, which OkHttp treats
         * as "no timeout", so without this bound a silent connection blocks the
         * request thread forever.
         */
        @DefaultValue("PT60S") Duration requestTimeout
) {
    public AiProperties {
        if (maxAttempts < 1) {
            throw new IllegalStateException("veridoc.ai.max-attempts must be >= 1");
        }
        if (maxAttempts > 10) {
            throw new IllegalStateException("veridoc.ai.max-attempts must be <= 10");
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalStateException("veridoc.ai.initial-backoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    "veridoc.ai.max-backoff must be >= initial-backoff");
        }
        if (requestTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("veridoc.ai.request-timeout must be >= 1s");
        }
        if (requestTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException("veridoc.ai.request-timeout must be <= 10m");
        }
    }
}