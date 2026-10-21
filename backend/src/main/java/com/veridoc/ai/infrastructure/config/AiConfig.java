package com.veridoc.ai.infrastructure.config;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

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
public class AiConfig {

    /**
     * Bounded retries with exponential backoff for transient provider failures.
     *
     * <p>Spring AI's Gemini embedding model consumes
     * {@code org.springframework.core.retry.RetryTemplate} (Spring Framework 7),
     * not the legacy Spring Retry type, so this is the template actually applied
     * to the embedding calls. The auto-configured template only retries
     * {@code TransientAiException}, which the Gemini model never raises, so
     * without this bean provider failures would never be retried.
     *
     * <p>Chat calls go to Groq through the official OpenAI SDK, whose model
     * takes no {@code RetryTemplate}: those retries are bounded by
     * {@code spring.ai.openai.max-retries} instead. {@link AiExceptions}
     * classifies failures from both transports.
     *
     * <p>The predicate is deliberately narrow: only timeouts, rate limits and
     * 5xx-class failures are retried (see {@link AiExceptions#isTransient}).
     * Validation errors and authentication failures fail fast because retrying
     * them cannot succeed. Attempts are bounded by
     * {@code veridoc.ai.max-attempts}, and the whole sequence is capped by
     * {@link #overallRetryBudget}, so a hard-down provider can never hang the
     * request thread indefinitely.
     */
    @Bean
    public RetryTemplate aiRetryTemplate(AiProperties properties) {
        RetryPolicy policy = RetryPolicy.builder()
                // maxRetries counts the retries after the initial call.
                .maxRetries(properties.maxAttempts() - 1L)
                .delay(properties.initialBackoff())
                .multiplier(2.0)
                .maxDelay(properties.maxBackoff())
                // Randomised jitter so concurrent clients do not synchronise
                // their retries against the provider.
                .jitter(properties.initialBackoff())
                .predicate(AiExceptions::isTransient)
                .timeout(overallRetryBudget(properties))
                .build();
        return new RetryTemplate(policy);
    }

    /**
     * Belt-and-suspenders ceiling: attempts are already bounded by
     * {@code max-attempts} and each HTTP request by {@code request-timeout}, so
     * this budget is only ever reached if backoff timing misbehaves.
     */
    private static Duration overallRetryBudget(AiProperties properties) {
        return properties.requestTimeout()
                .multipliedBy(properties.maxAttempts())
                .plus(properties.maxBackoff().multipliedBy(properties.maxAttempts()))
                .plus(properties.initialBackoff());
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