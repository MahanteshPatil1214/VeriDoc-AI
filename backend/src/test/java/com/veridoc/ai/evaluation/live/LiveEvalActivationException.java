package com.veridoc.ai.evaluation.live;

/**
 * Thrown when a live-provider evaluation run is attempted without the explicit
 * activation it requires.
 *
 * <p>This is a fail-closed signal, not a warning: the run must not continue,
 * and it must not continue <em>before</em> the application context, the
 * provider clients or the database are touched. The message names every
 * condition that failed.
 */
public class LiveEvalActivationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public LiveEvalActivationException(String message) {
        super(message);
    }
}
