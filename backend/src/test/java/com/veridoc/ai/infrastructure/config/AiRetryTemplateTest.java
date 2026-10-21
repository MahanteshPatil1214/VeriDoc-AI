package com.veridoc.ai.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryException;

import com.google.genai.errors.GenAiIOException;
import com.veridoc.ai.config.properties.AiProperties;

/**
 * The retry policy must be real and bounded: transient provider failures are
 * retried up to {@code veridoc.ai.max-attempts} and can recover, while
 * permanent failures fail fast. Backoffs are 1-2ms so the tests stay fast.
 */
class AiRetryTemplateTest {

    private final AiProperties properties = new AiProperties(
            "openai/gpt-oss-120b",
            "gemini-embedding-001",
            3,
            Duration.ofMillis(1),
            Duration.ofMillis(2),
            Duration.ofSeconds(60));

    private final org.springframework.core.retry.RetryTemplate template =
            new AiConfig().aiRetryTemplate(properties);

    @Test
    void transientFailureIsRetriedUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> template.<Void>execute(() -> {
            calls.incrementAndGet();
            throw new GenAiIOException("boom", new InterruptedIOException("read timed out"));
        }));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(thrown).isInstanceOf(RetryException.class);
    }

    @Test
    void transientFailureCanRecoverWithinTheAttemptBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = template.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new GenAiIOException("flaky");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void permanentFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> template.<Void>execute(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("model not found");
        }));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(thrown).isNotNull();
    }
}
