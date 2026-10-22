package com.veridoc.ai.evaluation.live;

import java.util.List;

/**
 * Thrown when the live-evaluation preflight rejects a run.
 *
 * <p>Extends {@link LiveEvalActivationException} so callers that already catch
 * the activation failure keep working. The message lists every violated
 * condition at once - activation, credentials, budget configuration - so an
 * operator fixes the whole environment in one pass instead of one problem per
 * run.
 *
 * <p>It never contains a credential value. Credential problems are reported as
 * {@code GEMINI_API_KEY is missing} / {@code is an obvious placeholder}, never
 * as a prefix, suffix or length of the value.
 */
public class LiveEvalPreflightException extends LiveEvalActivationException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    public LiveEvalPreflightException(List<String> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    /** Every condition that failed, in the order it was checked. */
    public List<String> violations() {
        return violations;
    }

    private static String message(List<String> violations) {
        StringBuilder message = new StringBuilder("live evaluation is not ready:");
        for (String violation : violations) {
            message.append(System.lineSeparator()).append("  - ").append(violation);
        }
        return message.toString();
    }
}
