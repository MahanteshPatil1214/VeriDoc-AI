package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the readiness preflight that guards every live run.
 *
 * <p>The preflight is pure: it takes a property map and an environment map, so
 * every fail-closed case can be exercised without credentials, without a
 * container, without a Spring context and without a socket.
 */
class LiveEvalPreflightTest {

    private static final String SYNTHETIC_GEMINI = "synthetic-gemini-credential-0001";
    private static final String SYNTHETIC_GROQ = "synthetic-groq-credential-0002";

    @Test
    @DisplayName("a missing opt-in property fails closed")
    void missingOptInFailsClosed() {
        Map<String, String> properties = Map.of(
                LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE);

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(properties, validCredentials()))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalActivation.OPT_IN_PROPERTY);
    }

    @Test
    @DisplayName("a missing live-eval profile fails closed even with the opt-in")
    void missingProfileFailsClosed() {
        Map<String, String> properties = Map.of(LiveEvalActivation.OPT_IN_PROPERTY, "true");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(properties, validCredentials()))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining("live-eval");
    }

    @Test
    @DisplayName("a CI environment fails closed even when fully activated")
    void ciEnvironmentFailsClosed() {
        Map<String, String> environment = validCredentials();
        environment.put(LiveEvalActivation.CI_ENVIRONMENT_VARIABLE, "true");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), environment))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalActivation.CI_ENVIRONMENT_VARIABLE);
    }

    @Test
    @DisplayName("a CI value of false still fails closed")
    void ciFalseStillFailsClosed() {
        Map<String, String> environment = validCredentials();
        environment.put(LiveEvalActivation.CI_ENVIRONMENT_VARIABLE, "false");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), environment))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalActivation.CI_ENVIRONMENT_VARIABLE);
    }

    @Test
    @DisplayName("missing credentials fail closed, naming both variables")
    void missingCredentialsFailClosed() {
        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), Map.of()))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalCredentials.GEMINI_VARIABLE + " is missing")
                .hasMessageContaining(LiveEvalCredentials.GROQ_VARIABLE + " is missing");
    }

    @Test
    @DisplayName("a placeholder credential fails closed")
    void placeholderCredentialsFailClosed() {
        Map<String, String> environment = validCredentials();
        environment.put(LiveEvalCredentials.GEMINI_VARIABLE, "test-key-not-used");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), environment))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalCredentials.GEMINI_VARIABLE + " is an obvious placeholder");
    }

    @Test
    @DisplayName("a blank credential fails closed")
    void blankCredentialsFailClosed() {
        Map<String, String> environment = validCredentials();
        environment.put(LiveEvalCredentials.GROQ_VARIABLE, "   ");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), environment))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining(LiveEvalCredentials.GROQ_VARIABLE + " is blank");
    }

    @Test
    @DisplayName("an invalid budget fails closed")
    void invalidBudgetFailsClosed() {
        Map<String, String> properties = activatedProperties();
        properties.put(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY, "0");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(properties, validCredentials()))
                .isInstanceOf(LiveEvalPreflightException.class)
                .hasMessageContaining("budget configuration")
                .hasMessageContaining(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY);
    }

    @Test
    @DisplayName("every violated condition is reported in one run")
    void allViolationsAreReportedTogether() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalBudget.MAX_CASES_PROPERTY, "-1");

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(properties, Map.of()))
                .isInstanceOf(LiveEvalPreflightException.class)
                .satisfies(thrown -> {
                    LiveEvalPreflightException exception = (LiveEvalPreflightException) thrown;
                    assertThat(exception.violations())
                            .as("two activation problems, two credential problems and one budget problem")
                            .hasSize(5);
                    assertThat(String.join(" | ", exception.violations()))
                            .contains(LiveEvalActivation.OPT_IN_PROPERTY)
                            .contains("live-eval")
                            .contains(LiveEvalCredentials.GEMINI_VARIABLE)
                            .contains(LiveEvalCredentials.GROQ_VARIABLE)
                            .contains(LiveEvalBudget.MAX_CASES_PROPERTY);
                });
    }

    @Test
    @DisplayName("a fully configured environment yields a fresh plan")
    void validConfigurationYieldsAPlan() {
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady(
                activatedProperties(), validCredentials());

        assertThat(plan.activation().enabled()).isTrue();
        assertThat(plan.activation().violations()).isEmpty();
        assertThat(plan.credentials().acceptable()).isTrue();
        assertThat(plan.budget().maxCases()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_CASES);
        assertThat(plan.budget().maxChatCalls()).isEqualTo(LiveEvalBudget.DEFAULT_MAX_CHAT_CALLS);
        assertThat(plan.budget().totalCalls()).isZero();
        assertThat(plan.budget().expired()).isFalse();
    }

    @Test
    @DisplayName("a plan carries credential statuses, never credential values")
    void planNeverCarriesCredentialValues() {
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady(
                activatedProperties(), validCredentials());

        assertThat(String.valueOf(plan))
                .doesNotContain(SYNTHETIC_GEMINI)
                .doesNotContain(SYNTHETIC_GROQ);
        assertThat(plan.credentials().describe())
                .doesNotContain(SYNTHETIC_GEMINI)
                .doesNotContain(SYNTHETIC_GROQ);
    }

    @Test
    @DisplayName("a rejected run never leaks the credential that was present")
    void rejectionNeverLeaksAValidCredential() {
        Map<String, String> environment = validCredentials();
        environment.remove(LiveEvalCredentials.GROQ_VARIABLE);

        assertThatThrownBy(() -> LiveEvalPreflight.requireReady(activatedProperties(), environment))
                .isInstanceOf(LiveEvalPreflightException.class)
                .satisfies(thrown -> assertThat(String.valueOf(thrown.getMessage()))
                        .doesNotContain(SYNTHETIC_GEMINI));
    }

    private static Map<String, String> activatedProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE);
        properties.put(LiveEvalActivation.OPT_IN_PROPERTY, "true");
        return properties;
    }

    private static Map<String, String> validCredentials() {
        Map<String, String> environment = new HashMap<>();
        environment.put(LiveEvalCredentials.GEMINI_VARIABLE, SYNTHETIC_GEMINI);
        environment.put(LiveEvalCredentials.GROQ_VARIABLE, SYNTHETIC_GROQ);
        return environment;
    }
}
