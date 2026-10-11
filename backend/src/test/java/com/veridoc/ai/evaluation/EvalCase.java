package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * One graded retrieval/answer scenario. {@code expectedDocument} is null (and
 * {@code expectRefusal} true) for questions the corpus deliberately cannot
 * answer; those exercise the no-evidence contract.
 */
public record EvalCase(
        String id,
        String question,
        String expectedDocument,
        List<String> expectedKeywords,
        boolean expectRefusal
) {
    public EvalCase {
        if (expectedKeywords == null) {
            expectedKeywords = List.of();
        }
    }

    public boolean isGrounded() {
        return !expectRefusal;
    }
}
