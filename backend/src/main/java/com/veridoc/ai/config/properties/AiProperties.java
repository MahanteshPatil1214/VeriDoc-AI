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

        @DefaultValue("gemini-2.5-flash") String chatModel,

        @DefaultValue("text-embedding-004") String embeddingModel,

        @DefaultValue("3") int maxAttempts,

        @DefaultValue("PT1S") Duration initialBackoff,

        @DefaultValue("PT20S") Duration maxBackoff
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
    }
}