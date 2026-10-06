package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chunking parameters. These are starting values and are expected to be tuned
 * against the RAG evaluation set, not treated as permanent.
 */
@ConfigurationProperties(prefix = "veridoc.chunking")
public record ChunkingProperties(

        @DefaultValue("700") int maxTokens,

        @DefaultValue("100") int overlapTokens,

        @DefaultValue("40") int minTokens
) {
    public ChunkingProperties {
        if (maxTokens <= 0) {
            throw new IllegalStateException("veridoc.chunking.max-tokens must be positive");
        }
        if (minTokens <= 0 || minTokens >= maxTokens) {
            throw new IllegalStateException(
                    "veridoc.chunking.min-tokens must be > 0 and < max-tokens");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalStateException(
                    "veridoc.chunking.overlap-tokens must be >= 0 and < max-tokens");
        }
    }
}