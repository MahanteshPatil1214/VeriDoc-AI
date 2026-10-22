package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.veridoc.ai.evaluation.live.LiveEvalCredentials.Entry;
import com.veridoc.ai.evaluation.live.LiveEvalCredentials.Report;
import com.veridoc.ai.evaluation.live.LiveEvalCredentials.Status;

/**
 * Behaviour of the credential presence check.
 *
 * <p>Every test here uses a synthetic environment map. No test reads the real
 * process environment, needs a credential, opens a socket or contacts a
 * provider.
 */
class LiveEvalCredentialsTest {

    /** Synthetic key that is long, non-blank and not a placeholder. */
    private static final String SYNTHETIC_GEMINI = "synthetic-gemini-credential-0001";

    /** Synthetic key that is long, non-blank and not a placeholder. */
    private static final String SYNTHETIC_GROQ = "synthetic-groq-credential-0002";

    @Test
    @DisplayName("both variables missing: both are rejected and named")
    void missingVariablesAreRejected() {
        Report report = LiveEvalCredentials.inspect(Map.of());

        assertThat(report.acceptable()).isFalse();
        assertThat(report.rejected()).extracting(Entry::variable)
                .containsExactly(LiveEvalCredentials.GEMINI_VARIABLE,
                        LiveEvalCredentials.GROQ_VARIABLE);
        assertThat(report.rejected()).extracting(Entry::status)
                .containsOnly(Status.MISSING);
    }

    @Test
    @DisplayName("a null environment is treated as two missing variables, not an error")
    void nullEnvironmentIsAllMissing() {
        Report report = LiveEvalCredentials.inspect(null);

        assertThat(report.acceptable()).isFalse();
        assertThat(report.rejected()).hasSize(2);
    }

    @ParameterizedTest(name = "blank value [{0}] is rejected")
    @ValueSource(strings = {"", "   ", "\t", "\n  \t"})
    @DisplayName("a blank value is rejected as blank, not as present")
    void blankValuesAreRejected(String blank) {
        Report report = LiveEvalCredentials.inspect(environment(blank, SYNTHETIC_GROQ));

        assertThat(report.acceptable()).isFalse();
        assertThat(statusOf(report, LiveEvalCredentials.GEMINI_VARIABLE)).isEqualTo(Status.BLANK);
        assertThat(statusOf(report, LiveEvalCredentials.GROQ_VARIABLE)).isEqualTo(Status.PRESENT);
    }

    @ParameterizedTest(name = "placeholder [{0}] is rejected")
    @ValueSource(strings = {
            "test-key-not-used",
            "TEST-KEY-NOT-USED",
            "changeme",
            "change-me",
            "your-api-key",
            "your_api_key_here",
            "replace-me-with-a-real-key",
            "<your-gemini-key>",
            "${GEMINI_API_KEY}",
            "xxxxxxxxxxxxxxxxxxxxxxxx",
            "************************",
            "00000000000000000000",
            "placeholder"
    })
    @DisplayName("an obvious placeholder is rejected as a placeholder")
    void placeholderValuesAreRejected(String placeholder) {
        Report report = LiveEvalCredentials.inspect(environment(placeholder, SYNTHETIC_GROQ));

        assertThat(report.acceptable()).isFalse();
        assertThat(statusOf(report, LiveEvalCredentials.GEMINI_VARIABLE)).isEqualTo(Status.PLACEHOLDER);
    }

    @ParameterizedTest(name = "too short [{0}] is rejected")
    @ValueSource(strings = {"a", "abc", "key1", "gsk_123", "short-key"})
    @DisplayName("a value too short to be a real key is rejected")
    void shortValuesAreRejected(String shortValue) {
        Report report = LiveEvalCredentials.inspect(environment(shortValue, SYNTHETIC_GROQ));

        assertThat(report.acceptable()).isFalse();
        assertThat(statusOf(report, LiveEvalCredentials.GEMINI_VARIABLE)).isEqualTo(Status.TOO_SHORT);
    }

    @Test
    @DisplayName("two plausible synthetic keys are accepted as present")
    void plausibleValuesAreAccepted() {
        Report report = LiveEvalCredentials.inspect(environment(SYNTHETIC_GEMINI, SYNTHETIC_GROQ));

        assertThat(report.acceptable()).isTrue();
        assertThat(report.rejected()).isEmpty();
        assertThat(report.entries()).extracting(Entry::status).containsOnly(Status.PRESENT);
    }

    @Test
    @DisplayName("a value with surrounding whitespace is stripped before the length check")
    void surroundingWhitespaceIsStripped() {
        Report report = LiveEvalCredentials.inspect(
                environment("  " + SYNTHETIC_GEMINI + "  ", SYNTHETIC_GROQ));

        assertThat(report.acceptable()).isTrue();
    }

    @Test
    @DisplayName("diagnostics name the variable and the reason, never the value")
    void diagnosticsNeverContainCredentialValues() {
        Report report = LiveEvalCredentials.inspect(environment(SYNTHETIC_GEMINI, SYNTHETIC_GROQ));

        assertThat(report.describe())
                .contains(LiveEvalCredentials.GEMINI_VARIABLE)
                .contains(LiveEvalCredentials.GROQ_VARIABLE)
                .doesNotContain(SYNTHETIC_GEMINI)
                .doesNotContain(SYNTHETIC_GROQ);
        assertThat(report.toString())
                .doesNotContain(SYNTHETIC_GEMINI)
                .doesNotContain(SYNTHETIC_GROQ);
        for (Entry entry : report.entries()) {
            assertThat(entry.toString())
                    .doesNotContain(SYNTHETIC_GEMINI)
                    .doesNotContain(SYNTHETIC_GROQ);
        }
    }

    @Test
    @DisplayName("redaction returns a category only, never a fragment or a length")
    void redactionReturnsACategoryOnly() {
        assertThat(LiveEvalCredentials.redact(null)).isEqualTo("<missing>");
        assertThat(LiveEvalCredentials.redact("   ")).isEqualTo("<blank>");
        assertThat(LiveEvalCredentials.redact("changeme")).isEqualTo("<placeholder>");
        assertThat(LiveEvalCredentials.redact(SYNTHETIC_GEMINI)).isEqualTo("<set>");
    }

    @Test
    @DisplayName("redaction of a rejected value reveals nothing about the value")
    void redactionOfAPlaceholderRevealsNothing() {
        String placeholder = "changeme-distinctive-marker-value";

        String redacted = LiveEvalCredentials.redact(placeholder);

        assertThat(redacted).isEqualTo("<placeholder>");
        assertThat(redacted).doesNotContain("distinctive").doesNotContain("marker");
    }

    private static Map<String, String> environment(String gemini, String groq) {
        Map<String, String> environment = new HashMap<>();
        environment.put(LiveEvalCredentials.GEMINI_VARIABLE, gemini);
        environment.put(LiveEvalCredentials.GROQ_VARIABLE, groq);
        return environment;
    }

    private static Status statusOf(Report report, String variable) {
        return report.entries().stream()
                .filter(entry -> entry.variable().equals(variable))
                .map(Entry::status)
                .findFirst()
                .orElseThrow();
    }
}
