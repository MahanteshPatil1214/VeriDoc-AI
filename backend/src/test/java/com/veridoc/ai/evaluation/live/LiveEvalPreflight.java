package com.veridoc.ai.evaluation.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The non-Spring preflight that every live run must pass before anything is
 * initialised.
 *
 * <p>It answers one question: <em>may this process create provider clients at
 * all?</em> It checks, and reports together:
 *
 * <ol>
 *   <li>the activation contract from {@link LiveEvalActivation} - the
 *       {@code live-eval} Maven profile, the {@code veridoc.eval.live=true}
 *       opt-in, and the absence of the {@code CI} variable;</li>
 *   <li>the credential presence check from {@link LiveEvalCredentials} -
 *       both keys exist, are non-blank, are not placeholders and are long
 *       enough to be plausible;</li>
 *   <li>the budget configuration from {@link LiveEvalBudget} - every limit is
 *       positive and below the absolute ceiling.</li>
 * </ol>
 *
 * <p>The result is a {@link Plan}; a plan cannot be constructed any other way,
 * so code that needs one has necessarily passed the preflight. It is pure: it
 * reads system properties and an environment map, touches no Spring context,
 * opens no socket and contacts no provider.
 *
 * <p><strong>Presence is not validity.</strong> Passing the preflight says the
 * configuration is complete enough to try. Whether the keys are accepted by
 * Gemini and Groq is only known after an authenticated probe, which is a
 * later, separately approved step.
 */
public final class LiveEvalPreflight {

    /**
     * A validated live run.
     *
     * @param activation the activation decision, always enabled
     * @param credentials the credential statuses, always acceptable
     * @param budget the shared budget, with its clock already started
     */
    public record Plan(LiveEvalActivation.Decision activation,
                       LiveEvalCredentials.Report credentials,
                       LiveEvalBudget budget) {

        public Plan {
            Objects.requireNonNull(activation, "activation must be provided");
            Objects.requireNonNull(credentials, "credentials must be provided");
            Objects.requireNonNull(budget, "budget must be provided");
            if (!activation.enabled()) {
                throw new IllegalArgumentException("a plan requires an enabled activation decision");
            }
            if (!credentials.acceptable()) {
                throw new IllegalArgumentException("a plan requires acceptable credentials");
            }
        }
    }

    private LiveEvalPreflight() {
    }

    /**
     * Runs the preflight against the real process state.
     *
     * @throws LiveEvalPreflightException when any condition fails
     */
    public static Plan requireReady() {
        return requireReady(System.getProperties(), System.getenv());
    }

    /**
     * Runs the preflight against an explicit environment.
     *
     * <p>Every failing condition is collected before throwing, so one run
     * reports the complete list.
     *
     * @param systemProperties the system properties (may be null)
     * @param environment the environment variables (may be null)
     * @throws LiveEvalPreflightException when any condition fails
     */
    public static Plan requireReady(Map<?, ?> systemProperties, Map<?, ?> environment) {
        List<String> violations = new ArrayList<>();

        LiveEvalActivation.Decision activation = LiveEvalGuard.evaluate(systemProperties, environment);
        violations.addAll(activation.violations());

        LiveEvalCredentials.Report credentials = LiveEvalCredentials.inspect(environment);
        for (LiveEvalCredentials.Entry rejected : credentials.rejected()) {
            violations.add("credential " + rejected.describe());
        }

        LiveEvalBudget budget = null;
        try {
            budget = LiveEvalBudget.from(systemProperties);
        } catch (LiveEvalBudgetException e) {
            violations.add("budget configuration: " + e.getMessage());
        }

        if (!violations.isEmpty()) {
            throw new LiveEvalPreflightException(violations);
        }
        return new Plan(activation, credentials, budget);
    }
}
