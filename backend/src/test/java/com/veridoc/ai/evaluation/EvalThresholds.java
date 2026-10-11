package com.veridoc.ai.evaluation;

/**
 * Aggregate gate for an evaluation run. Retrieval thresholds apply only to
 * grounded cases; the refusal-correctness gate applies to every case.
 */
public record EvalThresholds(
        int retrievalK,
        double minHitRate,
        double minMeanReciprocalRank,
        double minMeanKeywordCoverage,
        double minRefusalCorrectness
) {
    public static EvalThresholds core() {
        return new EvalThresholds(8, 1.0, 0.99, 0.75, 1.0);
    }
}
