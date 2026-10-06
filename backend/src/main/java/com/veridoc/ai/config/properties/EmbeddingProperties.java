package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding model identity. {@code dimensions} must match the pgvector column
 * width declared in {@code document_chunks.embedding}. {@code version} is
 * bumped whenever the pipeline changes so that incompatible vectors are never
 * silently mixed.
 */
@ConfigurationProperties(prefix = "veridoc.embedding")
public record EmbeddingProperties(

        @DefaultValue("text-embedding-004") String model,

        @DefaultValue("768") int dimensions,

        @DefaultValue("1") int version,

        @DefaultValue("32") int batchSize,

        @DefaultValue("RETRIEVAL_DOCUMENT") String documentTaskType,

        @DefaultValue("RETRIEVAL_QUERY") String queryTaskType
) {
    public EmbeddingProperties {
        if (dimensions <= 0 || dimensions > 2000) {
            throw new IllegalStateException(
                    "veridoc.embedding.dimensions must be in (0, 2000] and match the pgvector column");
        }
        if (batchSize <= 0) {
            throw new IllegalStateException("veridoc.embedding.batch-size must be positive");
        }
    }

    /** Canonical identity of the current embedding space. */
    public String fingerprint() {
        return model + ":" + dimensions + ":v" + version;
    }
}