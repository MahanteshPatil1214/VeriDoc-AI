package com.veridoc.ai.infrastructure.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.support.RetryTemplate;

import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.EmbeddingProperties;

/**
 * AI client wiring.
 *
 * <p>The deployment is pinned to exactly one embedding space, so document and
 * query embeddings always come from the same configured model. Mixing
 * providers or dimensions would silently corrupt retrieval, which is why
 * {@link EmbeddingDimensions} exists and why the auto-configured
 * {@link EmbeddingModel} is deliberately not wrapped in a second bean.
 *
 * <p>The Gemini API key is read on the backend from the environment. It is
 * never exposed to the frontend and never logged.
 */
@Configuration
@EnableRetry
public class AiConfig {

    /**
     * Bounded retries with exponential backoff for transient provider failures.
     * {@code retryOn} is deliberately narrow: only timeouts, rate limits and
     * 5xx-class failures are retried. Validation errors and authentication
     * failures fail fast because retrying them cannot succeed.
     */
    @Bean
    public RetryTemplate aiRetryTemplate(AiProperties properties) {
        return RetryTemplate.builder()
                // Hard ceiling: retries always terminate.
                .maxAttempts(properties.maxAttempts())
                .exponentialBackoff(
                        properties.initialBackoff(),
                        2.0,
                        properties.maxBackoff(),
                        // Randomised jitter so concurrent clients do not
                        // synchronise their retries against the provider.
                        true)
                .retryOn(AiExceptions::isTransient)
                .traversingCauses()
                .build();
    }

    /**
     * Which embedding model/dimensions this deployment is pinned to.
     *
     * <p>{@link AiReadinessProbe} is a separate component rather than a bean
     * method here so that its dependencies are injected normally and a missing
     * API key is reported once, at startup, instead of per request.
     *
     * <p>The grounding contract is applied where the prompt is assembled (see
     * {@code com.veridoc.ai.rag.prompt.GroundedPromptBuilder}), not globally on
     * the {@link ChatClient.Builder} prototype. Doing it per prompt keeps the
     * "evidence is not instruction" rules adjacent to the retrieved content
     * they govern, which is where a reviewer will actually look for them.
     */
    @Bean
    public EmbeddingDimensions embeddingDimensions(EmbeddingProperties properties) {
        return new EmbeddingDimensions(properties.model(), properties.dimensions(),
                properties.version());
    }

    public record EmbeddingDimensions(String model, int dimensions, int version) {

        public void requireMatch(int actualDimensions, String context) {
            if (actualDimensions != dimensions) {
                throw new IllegalStateException(
                        context + ": expected " + dimensions + " dimensions from " + model
                                + " but received " + actualDimensions
                                + ". Refusing to index an incompatible embedding space.");
            }
        }

        public String fingerprint() {
            return model + ":" + dimensions + ":v" + version;
        }
    }
}