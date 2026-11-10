package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Hybrid retrieval tuning: vector (pgvector cosine) + keyword (PostgreSQL tsvector / BM25). */
@ConfigurationProperties(prefix = "veridoc.retrieval")
public record RetrievalProperties(

        @DefaultValue("8") int topK,

        @DefaultValue("0.15") double minScore,

        @DefaultValue("40") int candidateK,

        @DefaultValue("20") int maxDocumentsPerConversation,

        @DefaultValue("true") boolean keywordEnabled,

        @DefaultValue("20") int keywordCandidateK,

        @DefaultValue("0.7") double vectorWeight,

        @DefaultValue("0.3") double keywordWeight
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
        if (keywordCandidateK < topK) {
            throw new IllegalStateException(
                    "veridoc.retrieval.keyword-candidate-k must be >= top-k");
        }
        double total = vectorWeight + keywordWeight;
        if (total <= 0.0 || total > 1.0 + 1e-9) {
            throw new IllegalStateException(
                    "veridoc.retrieval.vector-weight + veridoc.retrieval.keyword-weight must be in (0, 1]");
        }
        if (vectorWeight < 0.0 || keywordWeight < 0.0) {
            throw new IllegalStateException(
                    "veridoc.retrieval.vector-weight and keyword-weight must be non-negative");
        }
    }

    /** Normalised weight for the vector (cosine similarity) component. */
    public double normalisedVectorWeight() {
        double total = vectorWeight + keywordWeight;
        return total == 0.0 ? 1.0 : vectorWeight / total;
    }

    /** Normalised weight for the keyword (BM25 / ts_rank) component. */
    public double normalisedKeywordWeight() {
        double total = vectorWeight + keywordWeight;
        return total == 0.0 ? 0.0 : keywordWeight / total;
    }
}