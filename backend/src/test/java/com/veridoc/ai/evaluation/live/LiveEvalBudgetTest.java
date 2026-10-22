package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the run budget: parsing, validation and hard caps.
 *
 * <p>No test consumes a real budget slot twice by accident, and none needs a
 * credential, a container or a network connection.
 */
class LiveEvalBudgetTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("the defaults are the small measured-run budget")
    void defaultsAreConservative() {
        LiveEvalBudget budget = LiveEvalBudget.from(Map.of());

        assertThat(budget.maxCases()).isEqualTo(10);
        assertThat(budget.maxChatCalls()).isEqualTo(40);
        assertThat(budget.maxEmbeddingCalls()).isEqualTo(40);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(budget.totalCalls()).isZero();
        assertThat(budget.cases()).isZero();
        assertThat(budget.expired()).isFalse();
    }

    @Test
    @DisplayName("null properties fall back to the same defaults")
    void nullPropertiesFallBackToDefaults() {
        assertThat(LiveEvalBudget.from(null).maxChatCalls())
                .isEqualTo(LiveEvalBudget.DEFAULT_MAX_CHAT_CALLS);
    }

    @Test
    @DisplayName("every limit is configurable through a system property")
    void propertiesOverrideDefaults() {
        LiveEvalBudget budget = LiveEvalBudget.from(properties("3", "12", "7", "PT2M"));

        assertThat(budget.maxCases()).isEqualTo(3);
        assertThat(budget.maxChatCalls()).isEqualTo(12);
        assertThat(budget.maxEmbeddingCalls()).isEqualTo(7);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("a blank property is the same as an absent one")
    void blankPropertiesFallBackToDefaults() {
        LiveEvalBudget budget = LiveEvalBudget.from(properties(" ", "", "\t", "  "));

        assertThat(budget.maxCases()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_CASES);
        assertThat(budget.maxChatCalls()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_CHAT_CALLS);
        assertThat(budget.maxEmbeddingCalls()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_EMBEDDING_CALLS);
        assertThat(budget.maxDuration()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_DURATION);
    }

    @Test
    @DisplayName("a zero limit is a configuration error, for every limit")
    void zeroLimitsAreRejected() {
        assertThatThrownBy(() -> new LiveEvalBudget(0, 40, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_CASES_PROPERTY);
        assertThatThrownBy(() -> new LiveEvalBudget(10, 0, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY);
        assertThatThrownBy(() -> new LiveEvalBudget(10, 40, 0, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_EMBEDDING_CALLS_PROPERTY);
        assertThatThrownBy(() -> new LiveEvalBudget(10, 40, 40, Duration.ZERO))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_DURATION_PROPERTY);
    }

    @Test
    @DisplayName("a negative limit is a configuration error, for every limit")
    void negativeLimitsAreRejected() {
        assertThatThrownBy(() -> new LiveEvalBudget(-1, 40, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
        assertThatThrownBy(() -> new LiveEvalBudget(10, -5, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
        assertThatThrownBy(() -> new LiveEvalBudget(10, 40, -2, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
        assertThatThrownBy(() -> new LiveEvalBudget(10, 40, 40, Duration.ofMinutes(-1)))
                .isInstanceOf(LiveEvalBudgetException.class);
    }

    @Test
    @DisplayName("a null duration is a configuration error, not an unbounded run")
    void nullDurationIsRejected() {
        assertThatThrownBy(() -> new LiveEvalBudget(10, 40, 40, null))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_DURATION_PROPERTY);
    }

    @Test
    @DisplayName("a limit above the absolute ceiling is a configuration error")
    void absurdLimitsAreRejected() {
        assertThatThrownBy(() -> new LiveEvalBudget(
                LiveEvalBudget.ABSOLUTE_MAX_CASES + 1, 40, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
        assertThatThrownBy(() -> new LiveEvalBudget(
                10, LiveEvalBudget.ABSOLUTE_MAX_CALLS + 1, 40, Duration.ofMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
        assertThatThrownBy(() -> new LiveEvalBudget(
                10, 40, 40, LiveEvalBudget.ABSOLUTE_MAX_DURATION.plusMinutes(1)))
                .isInstanceOf(LiveEvalBudgetException.class);
    }

    @Test
    @DisplayName("a non-numeric property is a configuration error naming the property")
    void nonNumericPropertyIsRejected() {
        assertThatThrownBy(() -> LiveEvalBudget.from(properties("ten", "40", "40", "PT10M")))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_CASES_PROPERTY);
        assertThatThrownBy(() -> LiveEvalBudget.from(properties("10", "1.5", "40", "PT10M")))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY);
    }

    @Test
    @DisplayName("an unparseable duration is a configuration error naming the property")
    void unparseableDurationIsRejected() {
        assertThatThrownBy(() -> LiveEvalBudget.from(properties("10", "40", "40", "10 minutes")))
                .isInstanceOf(LiveEvalBudgetException.class)
                .hasMessageContaining(LiveEvalBudget.MAX_DURATION_PROPERTY)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    @DisplayName("the chat cap allows exactly the cap and rejects the next call")
    void chatCapAllowsTheCapAndRejectsTheNext() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 2, 40, Duration.ofMinutes(1));

        budget.recordChatCall();
        budget.recordChatCall();
        assertThat(budget.chatCalls()).isEqualTo(2);

        assertThatThrownBy(budget::recordChatCall)
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("chat")
                .hasMessageContaining("2 allowed")
                .hasMessageContaining("before the provider was contacted");

        assertThat(budget.chatCalls())
                .as("a rejected attempt stays counted, so a retry loop cannot spin")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the embedding cap allows exactly the cap and rejects the next call")
    void embeddingCapAllowsTheCapAndRejectsTheNext() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 40, 1, Duration.ofMinutes(1));

        budget.recordEmbeddingCall();

        assertThatThrownBy(budget::recordEmbeddingCall)
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("embedding")
                .hasMessageContaining("1 allowed");
        assertThat(budget.embeddingCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("the case cap allows exactly the cap and rejects the next case")
    void caseCapAllowsTheCapAndRejectsTheNext() {
        LiveEvalBudget budget = new LiveEvalBudget(2, 40, 40, Duration.ofMinutes(1));

        budget.recordCase();
        budget.recordCase();

        assertThatThrownBy(budget::recordCase)
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("case")
                .hasMessageContaining("2 allowed");
    }

    @Test
    @DisplayName("chat and embedding counters are independent")
    void countersAreIndependent() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 1, 1, Duration.ofMinutes(1));

        budget.recordChatCall();
        budget.recordEmbeddingCall();

        assertThat(budget.totalCalls()).isEqualTo(2);
        assertThat(budget.chatCalls()).isEqualTo(1);
        assertThat(budget.embeddingCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("the wall-clock cap rejects before counting and before any provider work")
    void wallClockCapRejectsBeforeCounting() {
        MutableClock clock = new MutableClock(START);
        LiveEvalBudget budget = new LiveEvalBudget(10, 40, 40, Duration.ofMinutes(5), clock);

        assertThat(budget.remainingTime()).isEqualTo(Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(4));
        assertThat(budget.expired()).isFalse();
        budget.recordChatCall();

        clock.advance(Duration.ofMinutes(1));
        assertThat(budget.expired())
                .as("the deadline is inclusive: at the deadline the budget is spent")
                .isTrue();
        assertThat(budget.remainingTime()).isZero();

        assertThatThrownBy(budget::recordEmbeddingCall)
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("time budget");

        assertThat(budget.embeddingCalls())
                .as("a call rejected by the clock was never charged")
                .isZero();
        assertThatThrownBy(budget::recordChatCall)
                .isInstanceOf(LiveEvalBudgetExceededException.class);
        assertThat(budget.chatCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a spent budget reports itself without any content")
    void summaryContainsCountersAndCapsOnly() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 2, 3, Duration.ofMinutes(1));
        budget.recordCase();
        budget.recordChatCall();
        budget.recordEmbeddingCall();

        assertThat(budget.summary())
                .contains("cases 1/10")
                .contains("chat 1/2")
                .contains("embedding 1/3");

        assertThat(budget.toString()).doesNotContain("prompt").doesNotContain("document");
    }

    @Test
    @DisplayName("a fresh budget accepts the first call of every kind")
    void freshBudgetAcceptsFirstCalls() {
        LiveEvalBudget budget = new LiveEvalBudget(1, 1, 1, Duration.ofMinutes(1));

        assertThatCode(() -> {
            budget.recordCase();
            budget.recordChatCall();
            budget.recordEmbeddingCall();
        }).doesNotThrowAnyException();
    }

    private static Map<String, String> properties(
            String maxCases, String maxChatCalls, String maxEmbeddingCalls, String maxDuration) {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalBudget.MAX_CASES_PROPERTY, maxCases);
        properties.put(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY, maxChatCalls);
        properties.put(LiveEvalBudget.MAX_EMBEDDING_CALLS_PROPERTY, maxEmbeddingCalls);
        properties.put(LiveEvalBudget.MAX_DURATION_PROPERTY, maxDuration);
        return properties;
    }

    /** A clock the test can move, so the ten-minute cap is testable in microseconds. */
    private static final class MutableClock extends Clock {

        private final ZoneId zone;
        private Instant instant;

        MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
