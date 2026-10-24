package com.veridoc.ai.evaluation.live;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The two authenticated provider probes: does Groq accept the chat credential,
 * and does Gemini accept the embedding credential?
 *
 * <p>This class is pure: it takes two models and an expected vector width, makes
 * exactly one invocation on each, and returns a {@link Report}. It has no Spring
 * annotation, opens no context and reads no configuration, so it can be tested
 * against fake models without a credential, a container or a socket.
 *
 * <p><strong>What is sent.</strong> Two short, synthetic, hard-coded strings -
 * see {@link #CHAT_PROBE_PROMPT} and {@link #EMBEDDING_PROBE_INPUT}. The
 * evaluation corpus is never read, and no document, case or answer reaches a
 * provider.
 *
 * <p><strong>What is reported.</strong> Provider name, pass/fail, elapsed
 * milliseconds and - on failure only - one safe category. Never a credential, a
 * prompt, a response body, document text, or an exception message: the
 * classification below reads exception <em>types</em> and HTTP status codes
 * only, because a provider error body can echo the request back.
 *
 * <p><strong>Invocations versus HTTP requests.</strong> One probe step is one
 * <em>model invocation</em>, which is also one budget unit charged by
 * {@link BudgetedChatModel} / {@link BudgetedEmbeddingModel}. It is not one
 * HTTP request. The probe itself never retries, but the production clients do:
 * the Groq chat client retries up to {@code spring.ai.openai.max-retries}, and
 * the Gemini embedding model runs under {@code AiConfig#aiRetryTemplate} with
 * {@code veridoc.ai.max-attempts}. A single invocation can therefore produce up
 * to three provider requests (the Gemini SDK's own retries are pinned to one
 * attempt by {@code GoogleGenAiHttpConfig}). Retries below the wrapper are
 * invisible to it, which is exactly why the budget also carries a wall-clock
 * deadline. Authentication failures are classified as permanent by
 * {@code AiExceptions}, so a bad key fails on the first attempt rather than
 * after the whole retry budget.
 *
 * <p><strong>Failed calls still cost.</strong> The decorator charges the budget
 * before delegating, so a rejected or failing invocation consumes its slot and
 * is never retried on the probe's side.
 */
public final class LiveProviderProbe {

    /** Synthetic chat input. No document text, never logged. */
    static final String CHAT_PROBE_PROMPT = "Reply with the single word: ready";

    /** Synthetic embedding input. No document text, never logged. */
    static final String EMBEDDING_PROBE_INPUT = "veridoc live provider probe";

    /** The chat provider name reported by the probe. */
    static final String CHAT_PROVIDER = "groq-chat";

    /** The embedding provider name reported by the probe. */
    static final String EMBEDDING_PROVIDER = "gemini-embedding";

    /**
     * Safe failure categories. Each one names a cause that an operator can act
     * on without ever seeing the provider's response body.
     */
    public enum FailureCategory {

        /** The budget refused the call, so no provider request was made. */
        BUDGET_EXHAUSTED,

        /** The response was null or carried no generation. */
        MISSING_RESPONSE,

        /** The response carried a blank text, or an empty vector. */
        EMPTY_RESPONSE,

        /** The vector width differs from the configured embedding dimension. */
        INVALID_DIMENSIONS,

        /** The vector contains NaN or an infinite value. */
        NON_FINITE_VECTOR,

        /** The vector is all zeros, i.e. structurally present but unusable. */
        ZERO_VECTOR,

        /** The provider rejected the credential (HTTP 401/403). */
        AUTHENTICATION,

        /** The provider rate limited the probe (HTTP 429). */
        RATE_LIMITED,

        /** The provider failed (HTTP 5xx). */
        PROVIDER_UNAVAILABLE,

        /** The request was rejected for a reason other than authentication (other 4xx). */
        REQUEST_REJECTED,

        /** The request timed out. */
        TIMEOUT,

        /** The provider could not be reached at all. */
        NETWORK,

        /** Anything the categories above do not describe. */
        UNEXPECTED_ERROR
    }

    /**
     * One provider's outcome.
     *
     * <p>The record deliberately carries no prompt, no response text and no
     * throwable, so a report can be printed or attached without leaking
     * content.
     *
     * @param provider the provider name
     * @param passed whether the probe succeeded
     * @param elapsedMillis the wall-clock time of the single invocation
     * @param failureCategory the safe category, null when the probe passed
     */
    public record Result(String provider, boolean passed, long elapsedMillis,
            FailureCategory failureCategory) {

        public Result {
            Objects.requireNonNull(provider, "provider must be provided");
            if (passed && failureCategory != null) {
                throw new IllegalArgumentException("a passing result cannot carry a failure category");
            }
            if (!passed && failureCategory == null) {
                throw new IllegalArgumentException("a failing result requires a failure category");
            }
            if (elapsedMillis < 0) {
                throw new IllegalArgumentException("elapsed time cannot be negative");
            }
        }

        /** A single safe report line. */
        public String describe() {
            String outcome = passed ? "PASS" : "FAIL";
            String category = passed ? "" : "  category=" + failureCategory;
            return String.format("%-18s %-4s %6d ms%s", provider, outcome, elapsedMillis, category);
        }
    }

    /**
     * The outcome of both probes.
     *
     * @param results one result per provider, in execution order
     */
    public record Report(List<Result> results) {

        public Report {
            results = List.copyOf(Objects.requireNonNull(results, "results must be provided"));
            if (results.isEmpty()) {
                throw new IllegalArgumentException("a report needs at least one result");
            }
        }

        /** Whether every probe passed. */
        public boolean allPassed() {
            return results.stream().allMatch(Result::passed);
        }

        /**
         * A multi-line, content-free report.
         *
         * <p>The header states the counting unit so a reader never mistakes one
         * invocation for one HTTP request.
         */
        public String summary() {
            String newline = System.lineSeparator();
            StringBuilder text = new StringBuilder("live provider credential probe")
                    .append(" (one model invocation per provider; provider-side HTTP retries")
                    .append(" are not counted)")
                    .append(newline);
            for (Result result : results) {
                text.append("  ").append(result.describe()).append(newline);
            }
            return text.append("verdict: ").append(allPassed() ? "PASS" : "FAIL").toString();
        }
    }

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final int expectedDimensions;

    /**
     * @param chatModel the real (budgeted) chat model, never null
     * @param embeddingModel the real (budgeted) embedding model, never null
     * @param expectedDimensions the configured embedding width, must be positive
     */
    public LiveProviderProbe(ChatModel chatModel, EmbeddingModel embeddingModel,
            int expectedDimensions) {
        this.chatModel = Objects.requireNonNull(chatModel, "a chat model must be provided");
        this.embeddingModel = Objects.requireNonNull(embeddingModel,
                "an embedding model must be provided");
        if (expectedDimensions <= 0) {
            throw new IllegalArgumentException("expected dimensions must be positive");
        }
        this.expectedDimensions = expectedDimensions;
    }

    /**
     * Runs both probes, each exactly once, and never lets one failure hide the
     * other.
     *
     * @return the report, safe to print
     */
    public Report run() {
        Result chat = probeChat();
        Result embedding = probeEmbedding();
        return new Report(List.of(chat, embedding));
    }

    /** One chat invocation, charged by the decorator before the delegate runs. */
    private Result probeChat() {
        long start = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(new Prompt(CHAT_PROBE_PROMPT));
            FailureCategory invalid = validate(response);
            return new Result(CHAT_PROVIDER, invalid == null, elapsedMillis(start), invalid);
        } catch (RuntimeException failure) {
            return new Result(CHAT_PROVIDER, false, elapsedMillis(start), classify(failure));
        }
    }

    /** One embedding invocation, charged by the decorator before the delegate runs. */
    private Result probeEmbedding() {
        long start = System.nanoTime();
        try {
            // The default embed(String) routes through call(EmbeddingRequest), so
            // the decorator charges this exactly once.
            float[] vector = embeddingModel.embed(EMBEDDING_PROBE_INPUT);
            FailureCategory invalid = validate(vector);
            return new Result(EMBEDDING_PROVIDER, invalid == null, elapsedMillis(start), invalid);
        } catch (RuntimeException failure) {
            return new Result(EMBEDDING_PROVIDER, false, elapsedMillis(start), classify(failure));
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** Structural validation of a chat response. Returns null when it is usable. */
    private static FailureCategory validate(ChatResponse response) {
        if (response == null) {
            return FailureCategory.MISSING_RESPONSE;
        }
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) {
            return FailureCategory.MISSING_RESPONSE;
        }
        Generation generation = generations.get(0);
        if (generation == null || generation.getOutput() == null) {
            return FailureCategory.MISSING_RESPONSE;
        }
        String text = generation.getOutput().getText();
        return text == null || text.isBlank() ? FailureCategory.EMPTY_RESPONSE : null;
    }

    /** Structural validation of an embedding vector. Returns null when usable. */
    private FailureCategory validate(float[] vector) {
        if (vector == null) {
            return FailureCategory.MISSING_RESPONSE;
        }
        if (vector.length == 0) {
            return FailureCategory.EMPTY_RESPONSE;
        }
        if (vector.length != expectedDimensions) {
            return FailureCategory.INVALID_DIMENSIONS;
        }
        boolean seenNonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return FailureCategory.NON_FINITE_VECTOR;
            }
            if (value != 0f) {
                seenNonZero = true;
            }
        }
        return seenNonZero ? null : FailureCategory.ZERO_VECTOR;
    }

    /**
     * Maps a failure to a safe category without reading a single message.
     *
     * <p>Exception messages are avoided on purpose: a provider error body often
     * echoes the request, and the request carries the credential header.
     */
    static FailureCategory classify(Throwable failure) {
        boolean budget = false;
        boolean timeout = false;
        boolean network = false;
        Integer status = null;

        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LiveEvalBudgetException) {
                budget = true;
            }
            if (current instanceof ApiException api && status == null) {
                status = api.code();
            }
            if (current instanceof OpenAIServiceException service && status == null) {
                status = service.statusCode();
            }
            if (current instanceof HttpStatusCodeException http && status == null) {
                status = http.getStatusCode().value();
            }
            if (current instanceof TimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof HttpTimeoutException) {
                timeout = true;
            }
            if (current instanceof GenAiIOException
                    || current instanceof OpenAIIoException
                    || current instanceof ResourceAccessException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                network = true;
            }
            if (current.getCause() == current) {
                break;
            }
        }

        if (budget) {
            // No provider request was made, so the provider's opinion is unknown.
            return FailureCategory.BUDGET_EXHAUSTED;
        }
        if (status != null) {
            return fromStatus(status);
        }
        if (timeout) {
            return FailureCategory.TIMEOUT;
        }
        if (network) {
            return FailureCategory.NETWORK;
        }
        return FailureCategory.UNEXPECTED_ERROR;
    }

    private static FailureCategory fromStatus(int status) {
        if (status == 401 || status == 403) {
            return FailureCategory.AUTHENTICATION;
        }
        if (status == 429) {
            return FailureCategory.RATE_LIMITED;
        }
        if (status >= 500 && status < 600) {
            return FailureCategory.PROVIDER_UNAVAILABLE;
        }
        if (status >= 400 && status < 500) {
            return FailureCategory.REQUEST_REJECTED;
        }
        return FailureCategory.UNEXPECTED_ERROR;
    }
}
