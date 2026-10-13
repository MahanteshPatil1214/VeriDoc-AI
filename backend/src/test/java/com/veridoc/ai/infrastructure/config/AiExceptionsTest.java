package com.veridoc.ai.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.veridoc.ai.common.error.ErrorCode;

/**
 * The Gemini SDK raises its own exception types, not Spring's
 * {@code HttpStatusCodeException}. These tests pin the mapping so that a
 * bounded timeout is surfaced to the client as {@code LLM_TIMEOUT} and a
 * transient provider failure is still retried.
 */
class AiExceptionsTest {

    @Test
    void genaiReadTimeoutIsTransientAndMapsToLlmTimeout() {
        Throwable timeout = new GenAiIOException("timeout", new SocketTimeoutException("read timed out"));

        assertThat(AiExceptions.isTransient(timeout)).isTrue();
        assertThat(AiExceptions.classify(timeout)).isEqualTo(ErrorCode.LLM_TIMEOUT);
    }

    @Test
    void genaiTransportFailureIsTransient() {
        Throwable failure = new GenAiIOException("connection reset", new java.io.IOException("reset"));

        assertThat(AiExceptions.isTransient(failure)).isTrue();
    }

    @Test
    void genaiServerExceptionIsTransientAndUnavailable() {
        Throwable server = new ServerException(503, "UNAVAILABLE", "model overloaded");

        assertThat(AiExceptions.isTransient(server)).isTrue();
        assertThat(AiExceptions.classify(server)).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
    }

    @Test
    void genaiRateLimitIsTransientAndRateLimited() {
        Throwable rateLimited = new ApiException(429, "RESOURCE_EXHAUSTED", "quota exceeded");

        assertThat(AiExceptions.isTransient(rateLimited)).isTrue();
        assertThat(AiExceptions.classify(rateLimited)).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
    }

    @Test
    void genaiClientErrorIsPermanent() {
        Throwable notFound = new ApiException(404, "NOT_FOUND", "model not found");

        assertThat(AiExceptions.isTransient(notFound)).isFalse();
    }

    @Test
    void timeoutExceptionStillMapsToLlmTimeout() {
        assertThat(AiExceptions.isTransient(new TimeoutException("slow"))).isTrue();
        assertThat(AiExceptions.classify(new TimeoutException("slow"))).isEqualTo(ErrorCode.LLM_TIMEOUT);
    }

    @Test
    void interruptedIoIsStillTransient() {
        assertThat(AiExceptions.isTransient(new InterruptedIOException("interrupted"))).isTrue();
    }
}
