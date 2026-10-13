package com.veridoc.ai.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.google.genai.types.HttpOptions;
import com.veridoc.ai.config.properties.AiProperties;

/**
 * Pins the per-request timeout and the single-attempt SDK retry policy, which is
 * what stops a silent Gemini connection from blocking a request thread forever
 * and keeps retries from multiplying with the application's own template.
 */
class GoogleGenAiHttpConfigTest {

    @Test
    void appliesTimeoutInMillisAndDisablesSdkRetries() {
        AiProperties properties = new AiProperties(
                "gemini-3.5-flash",
                "gemini-embedding-001",
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(20),
                Duration.ofSeconds(45));

        HttpOptions options = GoogleGenAiHttpConfig.httpOptions(properties);

        assertThat(options.timeout()).contains(45_000);
        assertThat(options.retryOptions()).isPresent();
        assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
    }
}
