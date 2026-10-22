package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * Binary, label-based retrieval metrics.
 *
 * <p>Every method takes the evidence that was actually retrieved and passed to
 * the model. Citations are intentionally <strong>not</strong> an accepted input:
 * an answer that cites the right document proves nothing about whether retrieval
 * found it, and using citations here was the defect this class corrects.
 *
 * <p>Pure functions: no Spring, no I/O, no similarity thresholds. Relevance
 * comes only from {@link RelevanceLabels}.
 *
 * <h2>Empty-input policy</h2>
 * Every method returns the worst value ({@code 0.0} / {@code false}) rather
 * than a vacuous {@code 1.0} when there is nothing to measure:
 * <ul>
 *   <li>no labelled evidence (an abstention case) — no relevant set exists, so
 *       recall and MRR are {@code 0.0}; callers exclude these cases from
 *       aggregates instead of treating the zero as a failure;</li>
 *   <li>nothing retrieved — nothing relevant can have been found, so all
 *       metrics are {@code 0.0};</li>
 *   <li>{@code k <= 0} — nothing is evaluated.</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * Number of retrieved passages in the first {@code k} whose document (and
     * page, when labelled) is relevant. Item-level: two relevant chunks on the
     * same page count twice, which is what precision needs.
     */
    public static int relevantRetrieved(List<EvidencePassage> retrieved,
                                        RelevanceLabels labels,
                                        int k) {
        if (retrieved == null || labels == null || labels.isEmpty() || k <= 0) {
            return 0;
        }
        int limit = Math.min(k, retrieved.size());
        int relevant = 0;
        for (int i = 0; i < limit; i++) {
            if (labels.matches(retrieved.get(i))) {
                relevant++;
            }
        }
        return relevant;
    }

    /**
     * {@code Recall@K = |labelled targets matched in retrieved[0,k)| / |labelled targets|}.
     *
     * <p>Target-level, so an expected document contributes exactly one unit
     * however many of its chunks were returned, and a multi-document case scores
     * {@code 0.5} when only one of its two documents was retrieved. Because the
     * dataset cannot enumerate every relevant chunk in the corpus, recall is
     * measured against the labels the case declares rather than against a
     * denominator the harness cannot verify.
     */
    public static double recallAtK(List<EvidencePassage> retrieved,
                                   RelevanceLabels labels,
                                   int k) {
        if (retrieved == null || labels == null || labels.isEmpty() || k <= 0) {
            return 0.0;
        }
        List<RelevanceLabels.RelevanceTarget> targets = labels.targets();
        int limit = Math.min(k, retrieved.size());
        int matched = 0;
        for (RelevanceLabels.RelevanceTarget target : targets) {
            for (int i = 0; i < limit; i++) {
                if (target.matches(retrieved.get(i))) {
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / targets.size();
    }

    /**
     * {@code Precision@K = relevant items in retrieved[0,k) / items evaluated},
     * where {@code items evaluated = min(k, retrieved.size())}.
     *
     * <p>Dividing by the number of items actually evaluated — not by {@code k} —
     * keeps a short result list from being penalised twice and stops a single
     * citation from producing the old constant {@code 1/8}. An empty result list
     * is {@code 0.0}.
     */
    public static double precisionAtK(List<EvidencePassage> retrieved,
                                      RelevanceLabels labels,
                                      int k) {
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0.0;
        }
        int evaluated = Math.min(k, retrieved.size());
        return (double) relevantRetrieved(retrieved, labels, k) / evaluated;
    }

    /** {@code 1/rank} of the first relevant retrieved passage, or {@code 0.0}. */
    public static double reciprocalRank(List<EvidencePassage> retrieved,
                                        RelevanceLabels labels) {
        if (retrieved == null || labels == null || labels.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            if (labels.matches(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Diagnostic only: {@code true} when any relevant passage appears in the
     * first {@code k}. This is exactly {@code recallAtK(...) > 0}, so it is not
     * independent evidence and should not be gated alongside recall.
     */
    public static boolean hitAtK(List<EvidencePassage> retrieved,
                                 RelevanceLabels labels,
                                 int k) {
        return relevantRetrieved(retrieved, labels, k) > 0;
    }
}
