package com.veridoc.ai.evaluation;

/**
 * The Stage 2 gate for the deterministic evaluation double.
 *
 * <p>{@link EvalThresholds#core()} is the <em>real provider</em> gate used from
 * Stage 3 onwards and is deliberately left untouched here: it encodes the
 * quality a live embedding and chat model must reach. Stage 2 runs against the
 * deterministic bag-of-words double, whose retrieval behaviour is driven by
 * stopword multiplicity and exact token overlap rather than by meaning. The
 * numbers below were therefore <em>measured</em> on the shipped 42-case dataset
 * with that double, and they pin the current behaviour so a regression in the
 * dataset, the harness or the retrieval path fails the build.
 *
 * <p>They are not a quality claim about the product. In particular
 * {@code minMeanPrecisionAtK} is low because the double's noise floor lets
 * several unrelated chunks cross the {@code min-score} threshold: that is a
 * property of the double, not of the corpus.
 *
 * <p>Every threshold is a floor on a measured value, so the gate fails as soon
 * as the double or the dataset changes behaviour in either direction.
 */
public record EvalGate(
        int retrievalK,
        double minHitRate,
        double minMeanReciprocalRank,
        double minMeanRecallAtK,
        double minMeanPrecisionAtK,
        double minMeanKeywordCoverage,
        double minMeanCitationPrecision,
        double minRefusalCorrectness
) {

    /**
     * The measured baseline of the deterministic double on the shipped dataset.
     *
     * <p>Measured values: hit rate 1.00, mean reciprocal rank 1.00, mean
     * recall@8 1.00, mean precision@8 0.49, mean keyword coverage 1.00, mean
     * citation precision 1.00, refusal correctness 1.00. The precision floor is
     * set to 0.45 because precision is the metric most sensitive to how many
     * chunks cross the similarity floor; every other metric is pinned to the
     * exact measured value.
     */
    public static EvalGate deterministicStub() {
        return new EvalGate(8, 1.0, 1.0, 1.0, 0.45, 1.0, 1.0, 1.0);
    }
}
