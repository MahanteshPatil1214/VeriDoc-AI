package com.veridoc.ai.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real (Gemini-backed) embedding client with the deterministic
 * stub for ingestion tests so the full pipeline runs against PostgreSQL
 * without any external call.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IngestionTestConfig {

    @Bean
    @Primary
    EmbeddingModel deterministicEmbeddingModel() {
        return new DeterministicEmbeddingModel();
    }
}