package com.veridoc.ai.infrastructure.config;

import java.util.concurrent.TimeoutException;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies AI provider failures as transient (worth retrying with backoff) or
 * permanent (retrying cannot succeed). Retries are always bounded by
 * {@code veridoc.ai.max-attempts}.
 *
 * <p>Both transports in use are recognised, so the classification does not
 * depend on which provider raised the failure:
 *
 * <ul>
 * <li>Gemini embeddings via the google-genai SDK — {@link ApiException} for
 * HTTP failures and {@link GenAiIOException} for transport-level faults
 * (including the read timeouts configured for {@code Client} in
 * {@link GoogleGenAiHttpConfig}).</li>
 * <li>Groq chat via the official OpenAI SDK — {@link OpenAIServiceException}
 * (and subclasses such as {@code RateLimitException}) for HTTP failures,
 * {@link OpenAIIoException} for transport faults, and
 * {@link OpenAIRetryableException} when the SDK's own bounded retries are
 * exhausted.</li>
 * </ul>
 *
 * <p>Spring's {@link HttpStatusCodeException} and the Spring AI retry
 * exceptions are handled as well, because intermediate layers may wrap the
 * provider failure in either.
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
            if (t instanceof OpenAIServiceException service) {
                int code = service.statusCode();
                if (code == 429 || (code >= 500 && code < 600)) {
                    return true;
                }
            }
            if (t instanceof OpenAIRetryableException) {
                // The SDK already retried this within its own bounded budget
                // and the provider still reported a retryable condition.
                return true;
            }
            if (t instanceof OpenAIIoException) {
                // Transport-level failure talking to Groq: worth another try.
                return true;
            }
            if (t instanceof NonTransientAiException) {
                // Classified permanent upstream: retrying cannot succeed.
                return false;
            }
            if (t instanceof TransientAiException) {
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
            if (t instanceof OpenAIServiceException service) {
                if (service.statusCode() == 429) {
                    return com.veridoc.ai.common.error.ErrorCode.LLM_RATE_LIMITED;
                }
                if (service.statusCode() >= 500) {
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