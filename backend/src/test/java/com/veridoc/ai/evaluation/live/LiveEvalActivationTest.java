package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The activation contract of the live harness.
 *
 * <p>These tests are the proof that a live run cannot start by accident. They
 * need no credentials, no network access and no paid API: the activation
 * logic is pure and is exercised with synthetic system properties and
 * environments.
 */
class LiveEvalActivationTest {

    /** The state of a properly activated invocation. */
    private static Map<String, String> activated() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE);
        properties.put(LiveEvalActivation.OPT_IN_PROPERTY, "true");
        return properties;
    }

    private static Map<String, String> environment(String... keyValues) {
        Map<String, String> environment = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            environment.put(keyValues[i], keyValues[i + 1]);
        }
        return environment;
    }

    @Test
    @DisplayName("live evaluation is disabled by default")
    void disabledByDefault() {
        LiveEvalActivation.Decision decision = LiveEvalActivation.evaluate(Map.of(), Map.of());

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations())
                .hasSize(2)
                .anySatisfy(violation -> assertThat(violation).contains("live-eval"))
                .anySatisfy(violation -> assertThat(violation).contains(LiveEvalActivation.OPT_IN_PROPERTY));
    }

    @Test
    @DisplayName("the Maven profile alone is not enough: the opt-in property is required")
    void missingOptInProperty() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE);

        LiveEvalActivation.Decision decision = LiveEvalActivation.evaluate(properties, Map.of());

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0)).contains(LiveEvalActivation.OPT_IN_PROPERTY);
        assertThat(LiveEvalActivation.isProfileActive(properties)).isTrue();
        assertThat(LiveEvalActivation.isOptIn(properties)).isFalse();
    }

    @Test
    @DisplayName("the opt-in property alone is not enough: the Maven profile is required")
    void missingMavenProfile() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalActivation.OPT_IN_PROPERTY, "true");

        LiveEvalActivation.Decision decision = LiveEvalActivation.evaluate(properties, Map.of());

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0)).contains("live-eval");
        assertThat(LiveEvalActivation.isProfileActive(properties)).isFalse();
        assertThat(LiveEvalActivation.isOptIn(properties)).isTrue();
    }

    @Test
    @DisplayName("the CI environment variable is detected whatever its value")
    void ciEnvironmentIsDetected() {
        assertThat(LiveEvalActivation.isCiEnvironment(Map.of())).isFalse();
        assertThat(LiveEvalActivation.isCiEnvironment(environment("CI", "true"))).isTrue();
        assertThat(LiveEvalActivation.isCiEnvironment(environment("CI", "1"))).isTrue();
        // Blank is not set; every other value counts, including "false",
        // because the check is fail-closed.
        assertThat(LiveEvalActivation.isCiEnvironment(environment("CI", "  "))).isFalse();
        assertThat(LiveEvalActivation.isCiEnvironment(environment("CI", "false"))).isTrue();
        assertThat(LiveEvalActivation.isCiEnvironment(null)).isFalse();
    }

    @Test
    @DisplayName("a CI machine blocks the run even when everything else is activated")
    void ciBlocksAnOtherwiseActivatedRun() {
        LiveEvalActivation.Decision decision =
                LiveEvalActivation.evaluate(activated(), environment("CI", "true"));

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0)).contains("CI");
    }

    @Test
    @DisplayName("profile, opt-in property and no CI together enable the run")
    void fullyActivatedRunIsEnabled() {
        LiveEvalActivation.Decision decision = LiveEvalActivation.evaluate(activated(), Map.of());

        assertThat(decision.enabled()).isTrue();
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.describe()).contains("explicitly activated");
    }

    @Test
    @DisplayName("the guard fails closed and names every violated condition")
    void guardFailsClosed() {
        LiveEvalActivation.Decision decision =
                LiveEvalActivation.evaluate(Map.of(), environment("CI", "true"));

        assertThatThrownBy(() -> LiveEvalGuard.assertLiveEvaluationEnabled(decision))
                .isInstanceOf(LiveEvalActivationException.class)
                .hasMessageContaining("CI")
                .hasMessageContaining("live-eval")
                .hasMessageContaining(LiveEvalActivation.OPT_IN_PROPERTY);
    }

    @Test
    @DisplayName("the guard passes an explicitly activated decision")
    void guardAllowsAnActivatedDecision() {
        LiveEvalActivation.Decision decision = LiveEvalActivation.evaluate(activated(), Map.of());

        org.assertj.core.api.Assertions.assertThatCode(
                () -> LiveEvalGuard.assertLiveEvaluationEnabled(decision))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the guard reads the real process state and refuses this test JVM")
    void guardRefusesTheRealTestJvm() {
        // The unit test JVM is not an activated live run, so the real guard
        // must refuse. Whichever condition fails here - the missing profile
        // marker, the missing opt-in property or a CI environment - the
        // outcome is the same: the run is rejected.
        assertThatThrownBy(() -> LiveEvalGuard.assertLiveEvaluationEnabled())
                .isInstanceOf(LiveEvalActivationException.class);
    }
}
