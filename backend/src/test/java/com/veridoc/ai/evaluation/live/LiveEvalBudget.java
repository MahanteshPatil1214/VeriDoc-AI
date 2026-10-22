package com.veridoc.ai.evaluation.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hard budget of one live-evaluation run: how many cases, how many chat
 * calls, how many embedding calls and how much wall-clock time it may spend.
 *
 * <p><strong>Counting is per model invocation, not per HTTP request.</strong>
 * The wrappers see one call each time application code invokes
 * {@code ChatModel.call(Prompt)}, {@code ChatModel.stream(Prompt)},
 * {@code EmbeddingModel.call(EmbeddingRequest)} or
 * {@code EmbeddingModel.embed(Document)}. A retry that travels back through the
 * wrapper - a caller retry, a replayed turn, a credential probe - is counted
 * again. A retry that happens <em>inside</em> the provider SDK is invisible
 * here: it happens below the wrapper, inside the delegate. The counts are
 * therefore a lower bound on the HTTP requests actually issued, and every cap
 * is set with headroom over the measured call counts rather than at them. The
 * duration cap is the backstop for that gap.
 *
 * <p>Every check runs before the delegate is invoked, so a rejected call costs
 * nothing: no prompt leaves the process and no token is billed. Counters are
 * {@link AtomicInteger}s, so two callers can never both squeeze into the last
 * slot; a rejected attempt stays counted, which makes the breach sticky instead
 * of letting a retry loop spin forever.
 *
 * <p>The limits are configurable through system properties, defaulted to a
 * deliberately small run:
 *
 * <table border="1">
 *   <caption>Budget properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@value #MAX_CASES_PROPERTY}</td><td>10</td><td>dataset cases</td></tr>
 *   <tr><td>{@value #MAX_CHAT_CALLS_PROPERTY}</td><td>40</td><td>chat model invocations</td></tr>
 *   <tr><td>{@value #MAX_EMBEDDING_CALLS_PROPERTY}</td><td>40</td><td>embedding model invocations</td></tr>
 *   <tr><td>{@value #MAX_DURATION_PROPERTY}</td><td>PT10M</td><td>ISO-8601 wall-clock cap</td></tr>
 * </table>
 */
public final class LiveEvalBudget {

    /** Property naming the maximum number of dataset cases. */
    public static final String MAX_CASES_PROPERTY = "veridoc.eval.live.max-cases";

    /** Property naming the maximum number of chat model invocations. */
    public static final String MAX_CHAT_CALLS_PROPERTY = "veridoc.eval.live.max-chat-calls";

    /** Property naming the maximum number of embedding model invocations. */
    public static final String MAX_EMBEDDING_CALLS_PROPERTY = "veridoc.eval.live.max-embedding-calls";

    /** Property naming the maximum wall-clock duration, as an ISO-8601 duration. */
    public static final String MAX_DURATION_PROPERTY = "veridoc.eval.live.max-duration";

    /** Ten cases keep the first measured run short and cheap. */
    public static final int DEFAULT_MAX_CASES = 10;

    /** Measured 10-case runs cost well under this; the headroom absorbs a retry. */
    public static final int DEFAULT_MAX_CHAT_CALLS = 40;

    /** Ingestion alone costs one call per document, so the embedding cap matches chat. */
    public static final int DEFAULT_MAX_EMBEDDING_CALLS = 40;

    /** Ten minutes is far longer than a 10-case run needs on a healthy provider. */
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(10);

    /** Ceiling that turns a typo such as {@code 100000} into a configuration error. */
    public static final int ABSOLUTE_MAX_CASES = 100;

    /** Ceiling that turns a typo such as {@code 100000} into a configuration error. */
    public static final int ABSOLUTE_MAX_CALLS = 1000;

    /** Ceiling that turns a typo such as {@code PT10H} into a configuration error. */
    public static final Duration ABSOLUTE_MAX_DURATION = Duration.ofMinutes(60);

    private final int maxCases;
    private final int maxChatCalls;
    private final int maxEmbeddingCalls;
    private final Duration maxDuration;
    private final Clock clock;
    private final Instant deadline;
    private final AtomicInteger cases = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger embeddingCalls = new AtomicInteger();

    /**
     * Creates a budget whose clock starts now.
     *
     * @throws LiveEvalBudgetException when any limit is zero, negative or above
     *         the absolute ceiling
     */
    public LiveEvalBudget(int maxCases, int maxChatCalls, int maxEmbeddingCalls, Duration maxDuration) {
        this(maxCases, maxChatCalls, maxEmbeddingCalls, maxDuration, Clock.systemUTC());
    }

    /**
     * Creates a budget against an explicit clock.
     *
     * <p>Package-visible so the deadline can be tested without waiting ten
     * minutes for it.
     */
    LiveEvalBudget(int maxCases, int maxChatCalls, int maxEmbeddingCalls, Duration maxDuration, Clock clock) {
        this.maxCases = requireLimit(MAX_CASES_PROPERTY, maxCases, ABSOLUTE_MAX_CASES);
        this.maxChatCalls = requireLimit(MAX_CHAT_CALLS_PROPERTY, maxChatCalls, ABSOLUTE_MAX_CALLS);
        this.maxEmbeddingCalls = requireLimit(MAX_EMBEDDING_CALLS_PROPERTY, maxEmbeddingCalls, ABSOLUTE_MAX_CALLS);
        this.maxDuration = requireDuration(maxDuration);
        if (clock == null) {
            throw new LiveEvalBudgetException("the budget clock must be provided");
        }
        this.clock = clock;
        this.deadline = clock.instant().plus(this.maxDuration);
    }

    /**
     * Builds a budget from system properties, applying the conservative
     * defaults for anything that is absent or blank.
     *
     * @param properties the system properties (may be null)
     * @throws LiveEvalBudgetException when a value is present but unusable
     */
    public static LiveEvalBudget from(Map<?, ?> properties) {
        return new LiveEvalBudget(
                intProperty(properties, MAX_CASES_PROPERTY, DEFAULT_MAX_CASES, ABSOLUTE_MAX_CASES),
                intProperty(properties, MAX_CHAT_CALLS_PROPERTY, DEFAULT_MAX_CHAT_CALLS, ABSOLUTE_MAX_CALLS),
                intProperty(properties, MAX_EMBEDDING_CALLS_PROPERTY, DEFAULT_MAX_EMBEDDING_CALLS, ABSOLUTE_MAX_CALLS),
                durationProperty(properties));
    }

    /**
     * Charges one dataset case.
     *
     * @throws LiveEvalBudgetExceededException when the case cap or the deadline
     *         is reached, before anything is asked
     */
    public void recordCase() {
        assertWithinDeadline();
        int attempt = cases.incrementAndGet();
        if (attempt > maxCases) {
            throw new LiveEvalBudgetExceededException(
                    "case budget exhausted: " + maxCases + " allowed, case " + attempt
                            + " rejected before any provider was contacted");
        }
    }

    /**
     * Charges one chat invocation.
     *
     * @throws LiveEvalBudgetExceededException when the chat cap or the deadline
     *         is reached, before the delegate is invoked
     */
    public void recordChatCall() {
        charge(chatCalls, "chat", maxChatCalls);
    }

    /**
     * Charges one embedding invocation.
     *
     * @throws LiveEvalBudgetExceededException when the embedding cap or the
     *         deadline is reached, before the delegate is invoked
     */
    public void recordEmbeddingCall() {
        charge(embeddingCalls, "embedding", maxEmbeddingCalls);
    }

    /** Fails when the wall-clock cap has been reached. */
    public void assertWithinDeadline() {
        if (expired()) {
            throw new LiveEvalBudgetExceededException(
                    "time budget exhausted: " + maxDuration + " allowed, next operation rejected"
                            + " before any provider was contacted");
        }
    }

    public int cases() {
        return cases.get();
    }

    public int chatCalls() {
        return chatCalls.get();
    }

    public int embeddingCalls() {
        return embeddingCalls.get();
    }

    public int totalCalls() {
        return chatCalls.get() + embeddingCalls.get();
    }

    public int maxCases() {
        return maxCases;
    }

    public int maxChatCalls() {
        return maxChatCalls;
    }

    public int maxEmbeddingCalls() {
        return maxEmbeddingCalls;
    }

    public Duration maxDuration() {
        return maxDuration;
    }

    public Instant deadline() {
        return deadline;
    }

    public Duration remainingTime() {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean expired() {
        return !clock.instant().isBefore(deadline);
    }

    /**
     * Counters and caps only.
     *
     * <p>Safe to print: the budget never holds a prompt, a document, an answer
     * or a credential.
     */
    public String summary() {
        return "cases " + cases() + "/" + maxCases
                + ", chat " + chatCalls() + "/" + maxChatCalls
                + ", embedding " + embeddingCalls() + "/" + maxEmbeddingCalls
                + ", remaining " + remainingTime();
    }

    private void charge(AtomicInteger counter, String kind, int max) {
        assertWithinDeadline();
        int attempt = counter.incrementAndGet();
        if (attempt > max) {
            throw new LiveEvalBudgetExceededException(kind + " call budget exhausted: " + max
                    + " allowed, call " + attempt + " rejected before the provider was contacted");
        }
    }

    private static int requireLimit(String name, int value, int absoluteMaximum) {
        if (value <= 0) {
            throw new LiveEvalBudgetException(name + " must be greater than zero, but was " + value);
        }
        if (value > absoluteMaximum) {
            throw new LiveEvalBudgetException(
                    name + " must not exceed " + absoluteMaximum + ", but was " + value);
        }
        return value;
    }

    private static Duration requireDuration(Duration value) {
        if (value == null) {
            throw new LiveEvalBudgetException(MAX_DURATION_PROPERTY + " must be provided");
        }
        if (value.isZero() || value.isNegative()) {
            throw new LiveEvalBudgetException(
                    MAX_DURATION_PROPERTY + " must be positive, but was " + value);
        }
        if (value.compareTo(ABSOLUTE_MAX_DURATION) > 0) {
            throw new LiveEvalBudgetException(
                    MAX_DURATION_PROPERTY + " must not exceed " + ABSOLUTE_MAX_DURATION + ", but was " + value);
        }
        return value;
    }

    private static int intProperty(Map<?, ?> properties, String key, int defaultValue, int absoluteMaximum) {
        Object raw = properties == null ? null : properties.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }
        String text = String.valueOf(raw).trim();
        try {
            return requireLimit(key, Integer.parseInt(text), absoluteMaximum);
        } catch (NumberFormatException e) {
            throw new LiveEvalBudgetException(key + " must be a whole number, but was '" + text + "'");
        }
    }

    private static Duration durationProperty(Map<?, ?> properties) {
        Object raw = properties == null ? null : properties.get(MAX_DURATION_PROPERTY);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return DEFAULT_MAX_DURATION;
        }
        String text = String.valueOf(raw).trim();
        try {
            return requireDuration(Duration.parse(text));
        } catch (DateTimeParseException e) {
            throw new LiveEvalBudgetException(
                    MAX_DURATION_PROPERTY + " must be an ISO-8601 duration such as PT10M, but was '" + text + "'");
        }
    }
}
