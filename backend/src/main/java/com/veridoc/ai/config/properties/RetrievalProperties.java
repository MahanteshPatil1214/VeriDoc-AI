package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Vector retrieval tuning. */
@ConfigurationProperties(prefix = "veridoc.retrieval")
public record RetrievalProperties(

        @DefaultValue("8") int topK,

        @DefaultValue("0.15") double minScore,

        @DefaultValue("40") int candidateK,

        @DefaultValue("20") int maxDocumentsPerConversation
) {
    public RetrievalProperties {
        if (topK <= 0) {
            throw new IllegalStateException("veridoc.retrieval.top-k must be positive");
        }
        if (candidateK < topK) {
            throw new IllegalStateException(
                    "veridoc.retrieval.candidate-k must be >= top-k");
        }
        if (minScore < -1.0 || minScore > 1.0) {
            throw new IllegalStateException(
                    "veridoc.retrieval.min-score must be a cosine similarity in [-1, 1]");
        }
        if (maxDocumentsPerConversation <= 0) {
            throw new IllegalStateException(
                    "veridoc.retrieval.max-documents-per-conversation must be positive");
        }
    }
}