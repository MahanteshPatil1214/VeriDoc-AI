package com.veridoc.ai.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.google.genai.Client;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;

/**
 * Guards the wiring that bounds Gemini network calls.
 *
 * <p>{@link GoogleGenAiHttpConfig} defines the {@code Client} bean, which makes
 * the embedding auto-configuration back off; if the embedding connection
 * details are not re-pointed at that same client, embeddings would silently
 * fall back to the SDK default (no timeout). This test pins the sharing so that
 * cannot regress.
 */
@Import(IngestionTestConfig.class)
class GoogleGenAiWiringIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private Client googleGenAiClient;

    @Autowired
    private GoogleGenAiEmbeddingConnectionDetails embeddingConnectionDetails;

    @Test
    void embeddingConnectionReusesTheSingleBoundedClientBean() {
        assertThat(embeddingConnectionDetails.getGenAiClient())
                .isSameAs(googleGenAiClient);
    }
}
