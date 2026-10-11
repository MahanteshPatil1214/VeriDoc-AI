package com.veridoc.ai.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic stubs for the query-rewriting pipeline: the embedding model is
 * the word-overlap stub (so retrieval is vocabulary-driven) and the chat model
 * additionally answers the rewrite prompt with a known retrievable query.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RewriteTestConfig {

    @Bean
    @Primary
    EmbeddingModel embeddingModel() {
        return new DeterministicEmbeddingModel();
    }

    @Bean
    @Primary
    ChatModel rewriteAwareChatModel() {
        return new RewriteAwareChatModel();
    }
}