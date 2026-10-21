package com.veridoc.ai.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import com.veridoc.ai.config.properties.AiProperties;

/**
 * Bounds every Gemini HTTP call.
 *
 * <p>Chat was moved to Groq (an OpenAI-compatible endpoint selected via
 * {@code spring.ai.model.chat=openai}); Gemini now serves embeddings only, and
 * this client exists for that path.
 *
 * <p>The google-genai SDK derives its connect/read/write timeouts from
 * {@link HttpOptions#timeout()} and leaves all three at zero when it is unset,
 * which OkHttp interprets as "wait forever". Spring AI 2.0.1 never sets it, so
 * a connection that is accepted and then goes silent blocks the request thread
 * indefinitely: a saturated provider shows up as a hang instead of an error.
 * Supplying this {@link Client} bean (the auto-configuration is
 * {@code @ConditionalOnMissingBean}) applies a hard per-attempt ceiling.
 *
 * <p>The SDK also retries on its own (five attempts by default), which would
 * multiply with the application's bounded {@code RetryTemplate}. Retries are
 * pinned to a single attempt here so the application owns the retry policy and
 * the worst-case wait stays bounded and predictable.
 *
 * <p>Defining a {@link Client} bean makes the embedding auto-configuration back
 * off, so the embedding connection details are provided explicitly and reuse
 * the same timed client. The connection properties are the
 * {@code spring.ai.google.genai.embedding.*} ones: the chat-level properties
 * ({@code spring.ai.google.genai.*}) are no longer registered once the chat
 * auto-configuration is switched off.
 */
@Configuration(proxyBeanMethods = false)
public class GoogleGenAiHttpConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiHttpConfig.class);

    static HttpOptions httpOptions(AiProperties properties) {
        return HttpOptions.builder()
                .timeout(Math.toIntExact(properties.requestTimeout().toMillis()))
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(Client.class)
    Client googleGenAiClient(GoogleGenAiEmbeddingConnectionProperties connection,
            AiProperties aiProperties) throws IOException {
        Client.Builder builder = Client.builder().httpOptions(httpOptions(aiProperties));
        log.info("Gemini embedding client configured: requestTimeout={}ms, sdkRetries=1 (application"
                + " RetryTemplate owns retries), vertexAi={}", aiProperties.requestTimeout().toMillis(),
                connection.isVertexAi());
        if (connection.isVertexAi()) {
            builder.vertexAI(true);
            if (connection.getProjectId() != null) {
                builder.project(connection.getProjectId());
            }
            if (connection.getLocation() != null) {
                builder.location(connection.getLocation());
            }
            Resource credentials = connection.getCredentialsUri();
            if (credentials != null) {
                try (InputStream in = credentials.getInputStream()) {
                    builder.credentials(GoogleCredentials.fromStream(in));
                }
            }
        } else if (StringUtils.hasText(connection.getApiKey())) {
            // Mirrors the upstream auto-configuration: a blank key must not be
            // passed to the SDK, which would fail later with an opaque error
            // instead of the readiness probe's actionable message.
            builder.apiKey(connection.getApiKey());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(GoogleGenAiEmbeddingConnectionDetails.class)
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            GoogleGenAiEmbeddingConnectionProperties connection, Client googleGenAiClient) {
        return GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(connection.getApiKey())
                .projectId(connection.getProjectId())
                .location(connection.getLocation())
                .genAiClient(googleGenAiClient)
                .build();
    }
}
