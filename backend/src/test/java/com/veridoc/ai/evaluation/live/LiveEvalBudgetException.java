package com.veridoc.ai.evaluation.live;

/**
 * Thrown when the live-evaluation budget is configured with a value that
 * cannot be used.
 *
 * <p>This is a configuration error, not a runtime breach: it is raised while
 * the preflight is assembling the run, before any Spring context, any provider
 * client and any network call exist. {@link LiveEvalBudgetExceededException}
 * extends it so a caller can catch both budget problems in one clause.
 */
public class LiveEvalBudgetException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public LiveEvalBudgetException(String message) {
        super(message);
    }
}
