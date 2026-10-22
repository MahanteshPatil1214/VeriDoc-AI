package com.veridoc.ai.evaluation.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The activation contract of the live-provider evaluation harness.
 *
 * <p>Live evaluation contacts Gemini and Groq and costs money, so it must be
 * impossible to start it by accident. Three independent conditions have to hold
 * at the same time:
 *
 * <ol>
 *   <li>the {@code live-eval} Maven profile is active. The profile is the only
 *       thing that puts the harness on Failsafe's include list, and it stamps
 *       the {@value #PROFILE_PROPERTY} system property into the forked JVM so
 *       the harness can tell that the profile really was active;</li>
 *   <li>the {@value #OPT_IN_PROPERTY} system property is {@code true}. This is
 *       a per-invocation opt-in that lives on the command line
 *       ({@code -Dveridoc.eval.live=true}) and cannot be implied by the
 *       profile alone;</li>
 *   <li>the {@value #CI_ENVIRONMENT_VARIABLE} environment variable is not set.
 *       A live run must never happen on a CI machine, whatever else is
 *       configured.</li>
 * </ol>
 *
 * <p>The logic is deliberately pure: it takes the system properties and the
 * environment as arguments, so it can be unit-tested without credentials,
 * without a Spring context, without network access and without touching the
 * real process environment. That is what makes the fail-closed behaviour
 * testable at all - the interesting cases (CI detected, property missing,
 * profile missing) are exactly the ones that cannot be reproduced by running
 * the real harness.
 */
public final class LiveEvalActivation {

    /**
     * System property stamped by the {@code live-eval} Maven profile.
     *
     * <p>Its value is {@value #PROFILE_ACTIVE_VALUE} when the profile is
     * active. Anything else - including a missing property - means the profile
     * is not active.
     */
    public static final String PROFILE_PROPERTY = "veridoc.eval.live.profile";

    /** The value the {@code live-eval} Maven profile stamps into the JVM. */
    public static final String PROFILE_ACTIVE_VALUE = "active";

    /**
     * System property that opts a single invocation into live evaluation.
     *
     * <p>Set on the command line with {@code -Dveridoc.eval.live=true}. It is
     * deliberately not part of any Maven profile: an operator has to type it
     * for every live run.
     */
    public static final String OPT_IN_PROPERTY = "veridoc.eval.live";

    /**
     * Environment variable that marks a CI machine.
     *
     * <p>Any non-blank value counts as CI, including {@code false}: the check
     * is fail-closed, so an unexpected value blocks the run rather than
     * allowing it.
     */
    public static final String CI_ENVIRONMENT_VARIABLE = "CI";

    private LiveEvalActivation() {
    }

    /**
     * The outcome of the activation check.
     *
     * @param enabled true only when every activation condition holds
     * @param violations the conditions that failed, empty when enabled
     */
    public record Decision(boolean enabled, List<String> violations) {

        public Decision {
            violations = List.copyOf(violations);
        }

        /** A human-readable explanation of why the run may not proceed. */
        public String describe() {
            if (enabled) {
                return "live evaluation is explicitly activated";
            }
            return "live evaluation is not allowed:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), violations.stream().map(v -> "  - " + v).toList());
        }
    }

    /**
     * Decides whether a live run may proceed.
     *
     * @param systemProperties the JVM system properties (may be null)
     * @param environment the process environment (may be null)
     * @return the decision; {@link Decision#enabled()} is true only when the
     *         profile is active, the opt-in property is {@code true} and the CI
     *         variable is absent
     */
    public static Decision evaluate(Map<?, ?> systemProperties, Map<?, ?> environment) {
        List<String> violations = new ArrayList<>();

        if (isCiEnvironment(environment)) {
            violations.add("the environment variable '" + CI_ENVIRONMENT_VARIABLE
                    + "' is set; live evaluation must never run on a CI machine");
        }
        if (!isProfileActive(systemProperties)) {
            violations.add("the Maven profile 'live-eval' is not active (expected system property "
                    + PROFILE_PROPERTY + "=" + PROFILE_ACTIVE_VALUE + ")");
        }
        if (!isOptIn(systemProperties)) {
            violations.add("the opt-in system property " + OPT_IN_PROPERTY
                    + "=true is missing; a live run must be requested explicitly");
        }

        return new Decision(violations.isEmpty(), violations);
    }

    /**
     * Whether the {@code live-eval} Maven profile is active.
     *
     * @param systemProperties the JVM system properties (may be null)
     */
    public static boolean isProfileActive(Map<?, ?> systemProperties) {
        return PROFILE_ACTIVE_VALUE.equals(property(systemProperties, PROFILE_PROPERTY));
    }

    /**
     * Whether the per-invocation opt-in property is present and true.
     *
     * @param systemProperties the JVM system properties (may be null)
     */
    public static boolean isOptIn(Map<?, ?> systemProperties) {
        String value = property(systemProperties, OPT_IN_PROPERTY);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /**
     * Whether the process looks like a CI machine.
     *
     * <p>Any non-blank value of {@value #CI_ENVIRONMENT_VARIABLE} counts. The
     * check is deliberately blunt: it errs towards refusing the run.
     *
     * @param environment the process environment (may be null)
     */
    public static boolean isCiEnvironment(Map<?, ?> environment) {
        if (environment == null) {
            return false;
        }
        Object value = environment.get(CI_ENVIRONMENT_VARIABLE);
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String property(Map<?, ?> properties, String key) {
        if (properties == null) {
            return null;
        }
        Object value = properties.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
