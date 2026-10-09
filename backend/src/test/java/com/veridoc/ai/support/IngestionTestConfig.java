package com.veridoc.ai.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real (Gemini-backed) chat and embedding clients with
 * deterministic stubs so the full upload/retrieval/chat pipeline runs against
 * PostgreSQL without any external call.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IngestionTestConfig {

    @Bean
    @Primary
    EmbeddingModel deterministicEmbeddingModel() {
        return new DeterministicEmbeddingModel();
    }

    @Bean
    @Primary
    ChatModel deterministicChatModel() {
        return new DeterministicChatModel();
    }
}