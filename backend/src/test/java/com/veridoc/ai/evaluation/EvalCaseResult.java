package com.veridoc.ai.evaluation;

/**
 * Graded outcome of one case: the retrieval rank metrics, answer keyword
 * coverage, whether the refusal behaviour was correct, and citation accuracy.
 */
public record EvalCaseResult(
        EvalCase evalCase,
        EvalObservation observation,
        boolean hit,
        double reciprocalRank,
        double precisionAtK,
        double recallAtK,
        double keywordCoverage,
        boolean refusalCorrect,
        double citationAccuracy
) {
}
