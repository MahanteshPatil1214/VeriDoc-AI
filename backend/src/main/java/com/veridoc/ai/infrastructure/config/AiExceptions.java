package com.veridoc.ai.infrastructure.config;

import java.util.concurrent.TimeoutException;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies AI provider failures as transient (worth retrying with backoff) or
 * permanent (retrying cannot succeed). Retries are always bounded by
 * {@code veridoc.ai.max-attempts}.
 *
 * <p>Both Spring's {@link HttpStatusCodeException} and the google-genai SDK's
 * {@link ApiException} are recognised, so the classification does not depend on
 * which transport raised the failure. The SDK's {@link GenAiIOException} wraps
 * transport-level faults (including the read timeouts configured for
 * {@code Client} in {@link GoogleGenAiHttpConfig}).
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
            if (t instanceof ApiException api) {
                int code = api.code();
                if (code == 429 || (code >= 500 && code < 600)) {
                    return true;
                }
            }
            if (t instanceof GenAiIOException) {
                // Failing to talk to the provider at all is worth another try.
                return true;
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
            if (t instanceof ApiException api) {
                if (api.code() == 429) {
                    return com.veridoc.ai.common.error.ErrorCode.LLM_RATE_LIMITED;
                }
                if (api.code() >= 500) {
                    return com.veridoc.ai.common.error.ErrorCode.LLM_UNAVAILABLE;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return com.veridoc.ai.common.error.ErrorCode.LLM_UNAVAILABLE;
    }
}