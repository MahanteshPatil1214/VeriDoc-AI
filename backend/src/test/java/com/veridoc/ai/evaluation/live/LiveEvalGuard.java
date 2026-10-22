package com.veridoc.ai.evaluation.live;

import java.util.Map;

/**
 * The gate every live-provider evaluation run has to pass.
 *
 * <p>The guard is the last line of defence. Two structural barriers already
 * keep an accidental live run from happening:
 *
 * <ul>
 *   <li>the harness class name does not match {@code **&#47;*IT.java}, so the
 *       default Failsafe include never picks it up and {@code mvn verify} does
 *       not execute it;</li>
 *   <li>the {@code live-eval} Maven profile is the only thing that adds the
 *       harness to Failsafe's include list, and it is never active by
 *       default.</li>
 * </ul>
 *
 * <p>This guard covers what those barriers cannot: a developer who activates
 * the profile, who passes the opt-in property in an environment that is
 * actually a CI machine, or who runs the harness class directly through
 * {@code -Dtest=...}. In every one of those cases the guard throws
 * {@link LiveEvalActivationException} before the Spring context is created,
 * before any provider client is built and before any network call is made.
 */
public final class LiveEvalGuard {

    private LiveEvalGuard() {
    }

    /** The decision for the current process, read from the real JVM state. */
    public static LiveEvalActivation.Decision current() {
        return evaluate(System.getProperties(), System.getenv());
    }

    /**
     * The decision for an explicit environment.
     *
     * <p>The preflight uses this to collect every activation violation together
     * with the credential and budget violations, instead of failing on the
     * first one. The behaviour is identical to
     * {@link LiveEvalActivation#evaluate(java.util.Map, java.util.Map)}.
     *
     * @param systemProperties the system properties (may be null)
     * @param environment the environment variables (may be null)
     */
    public static LiveEvalActivation.Decision evaluate(Map<?, ?> systemProperties, Map<?, ?> environment) {
        return LiveEvalActivation.evaluate(systemProperties, environment);
    }

    /**
     * Fails unless the current process is explicitly allowed to run a live
     * evaluation.
     *
     * @throws LiveEvalActivationException when any activation condition fails
     */
    public static void assertLiveEvaluationEnabled() {
        assertLiveEvaluationEnabled(current());
    }

    /**
     * Fails unless the given decision allows a live evaluation.
     *
     * <p>Package-visible so the fail-closed behaviour can be unit-tested
     * against a decision built from synthetic properties, without touching the
     * real environment of the test JVM.
     *
     * @throws LiveEvalActivationException when the decision is not enabled
     */
    static void assertLiveEvaluationEnabled(LiveEvalActivation.Decision decision) {
        if (!decision.enabled()) {
            throw new LiveEvalActivationException(decision.describe());
        }
    }
}
