package com.veridoc.ai.infrastructure.config;

import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies AI provider failures as transient (worth retrying with backoff) or
 * permanent (retrying cannot succeed). Retries are always bounded by
 * {@code veridoc.ai.max-attempts}.
 */
public final class AiExceptions {

    private AiExceptions() {
    }

    /**
     * Transient: timeouts, rate limits, 5xx and connection failures.
     * Everything else (auth failures, bad model names, 4xx) is permanent.
     */
    public static boolean isTransient(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
            if (t instanceof HttpStatusCodeException http) {
                HttpStatusCode status = http.getStatusCode();
                // 429 and 5xx are the provider telling us to try again.
                if (status.value() == 429 || status.is5xxServerError()) {
                    return true;
                }
            }
            if (t instanceof ResourceAccessException) {
                return true;
            }
            if (t instanceof java.io.InterruptedIOException) {
                return true;
            }
            // Do not walk into our own AppException: it has already been
            // classified by the layer that raised it.
            if (t instanceof com.veridoc.ai.common.error.AppException) {
                return false;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Maps a provider failure to a user-facing error code. */
    public static com.veridoc.ai.common.error.ErrorCode classify(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof java.io.InterruptedIOException) {
                return com.veridoc.ai.common.error.ErrorCode.LLM_TIMEOUT;
            }
            if (t instanceof HttpStatusCodeException http && http.getStatusCode().value() == 429) {
                return com.veridoc.ai.common.error.ErrorCode.LLM_RATE_LIMITED;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return com.veridoc.ai.common.error.ErrorCode.LLM_UNAVAILABLE;
    }
}