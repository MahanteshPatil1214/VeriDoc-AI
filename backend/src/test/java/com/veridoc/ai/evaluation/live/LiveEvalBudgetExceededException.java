package com.veridoc.ai.evaluation.live;

/**
 * Thrown when an operation would exceed the live-evaluation budget.
 *
 * <p>It is raised <strong>before</strong> the delegate is invoked, so the
 * provider is never contacted by a call that is rejected. It also carries no
 * prompt text, document content, answer text or credential: only counters and
 * configured caps appear in the message.
 */
public class LiveEvalBudgetExceededException extends LiveEvalBudgetException {

    private static final long serialVersionUID = 1L;

    public LiveEvalBudgetExceededException(String message) {
        super(message);
    }
}
