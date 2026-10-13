package com.veridoc.ai.infrastructure.config;

import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import com.veridoc.ai.config.properties.EmbeddingProperties;

/**
 * Builds the canonical embedding-request options for this deployment.
 *
 * <p>Both ingestion and query embedding must go through here so that every
 * vector is produced by the same model, at the same dimensionality and with the
 * matching task type. Relying on provider/auto-config defaults instead would
 * let the document and query paths drift apart, which silently corrupts
 * retrieval — exactly what {@code EmbeddingDimensions} exists to prevent.
 */
public final class EmbeddingOptionsFactory {

    private EmbeddingOptionsFactory() {
    }

    /** Options for indexing document chunks ({@code RETRIEVAL_DOCUMENT}). */
    public static GoogleGenAiTextEmbeddingOptions forDocuments(EmbeddingProperties properties) {
        return build(properties, properties.documentTaskType());
    }

    /** Options for embedding a search query ({@code RETRIEVAL_QUERY}). */
    public static GoogleGenAiTextEmbeddingOptions forQuery(EmbeddingProperties properties) {
        return build(properties, properties.queryTaskType());
    }

    private static GoogleGenAiTextEmbeddingOptions build(EmbeddingProperties properties,
                                                         String taskType) {
        return GoogleGenAiTextEmbeddingOptions.builder()
                .model(properties.model())
                .dimensions(properties.dimensions())
                .taskType(taskType(taskType))
                .build();
    }

    private static GoogleGenAiTextEmbeddingOptions.TaskType taskType(String value) {
        try {
            return GoogleGenAiTextEmbeddingOptions.TaskType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown embedding task type: " + value, e);
        }
    }
}