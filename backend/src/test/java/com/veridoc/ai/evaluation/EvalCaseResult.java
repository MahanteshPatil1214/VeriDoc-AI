package com.veridoc.ai.evaluation;

/**
 * Graded outcome of one case.
 *
 * <p>Retrieval fields ({@code hit}, {@code reciprocalRank}, {@code precisionAtK},
 * {@code recallAtK}) are computed from the retrieved evidence only. The
 * citation fields are computed from the model's actual citations only.
 *
 * <p>{@code pageCitationPrecision} and {@code pageCitationRecall} are
 * <em>page-applicable</em> scores: they are meaningful only for a case that
 * declares expected pages. For every other case they are {@code 0.0} by
 * convention, and such cases are excluded from the page aggregates — see
 * {@link CitationMetrics#pageLabelsApplicable(RelevanceLabels)}.
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
        double citationPrecision,
        double pageCitationPrecision,
        double pageCitationRecall
) {
}
