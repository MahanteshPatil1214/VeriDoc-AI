package com.veridoc.ai.evaluation;

import java.util.List;
import java.util.Locale;

import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;

/**
 * Aggregated outcome of an evaluation run, plus a human-readable summary.
 *
 * <p>The page-level citation metrics ({@link #meanPageCitationPrecision()},
 * {@link #meanPageCitationRecall()}) are reported but not gated: the
 * deterministic-stub gate {@link EvalGate#deterministicStub()} and the
 * real-provider gate {@link EvalThresholds#core()} are both left exactly as they
 * were, so adding the metrics cannot silently change what the build enforces.
 * A threshold for them belongs with the Stage 3 live thresholds.
 */
public record EvalReport(
        String datasetName,
        List<CorpusDocument> corpus,
        List<EvalCaseResult> results,
        int retrievalK
) {
    public EvalReport {
        results = List.copyOf(results);
        corpus = List.copyOf(corpus);
    }

    /** Fraction of grounded cases whose labelled evidence was retrieved at all. */
    public double hitRate() {
        return mean(grounded(), result -> result.hit() ? 1.0 : 0.0);
    }

    public double meanReciprocalRank() {
        return mean(grounded(), EvalCaseResult::reciprocalRank);
    }

    public double meanPrecisionAtK() {
        return mean(grounded(), EvalCaseResult::precisionAtK);
    }

    public double meanRecallAtK() {
        return mean(grounded(), EvalCaseResult::recallAtK);
    }

    public double meanKeywordCoverage() {
        return mean(grounded(), EvalCaseResult::keywordCoverage);
    }

    /**
     * Mean document-level citation precision over grounded cases.
     *
     * <p>Ungrounded cases are excluded: their citation precision is
     * {@code 0.0} by policy, and averaging that in would measure the refusal
     * mix rather than citation quality.
     */
    public double meanCitationPrecision() {
        return mean(grounded(), EvalCaseResult::citationPrecision);
    }

    /** Fraction of all cases whose refusal behaviour matched expectation. */
    public double refusalCorrectness() {
        return mean(results, result -> result.refusalCorrect() ? 1.0 : 0.0);
    }

    /**
     * Mean page-level citation precision over page-applicable cases.
     *
     * <p>Only cases that declare expected pages are averaged. A case without
     * page expectations has nothing to measure and is excluded rather than
     * counted as a failure — see
     * {@link CitationMetrics#pageLabelsApplicable(RelevanceLabels)}.
     */
    public double meanPageCitationPrecision() {
        return mean(pageApplicable(), EvalCaseResult::pageCitationPrecision);
    }

    /**
     * Mean page-level citation recall over page-applicable cases.
     *
     * <p>Averaged over the same slice as {@link #meanPageCitationPrecision()}.
     */
    public double meanPageCitationRecall() {
        return mean(pageApplicable(), EvalCaseResult::pageCitationRecall);
    }

    /** How many cases the page metrics were computed over. */
    public int pageApplicableCaseCount() {
        return pageApplicable().size();
    }

    /**
     * How many citations to an expected document carried no page number.
     *
     * <p>Diagnostic only. Such a citation is scored as incorrect by the page
     * precision metric; this counter keeps "the model cited the wrong page" and
     * "the chunk carries no page metadata" distinguishable.
     */
    public long missingPageCitationCount() {
        long missing = 0;
        for (EvalCaseResult result : pageApplicable()) {
            missing += CitationMetrics.missingPageCitationCount(
                    result.observation().citations(), result.evalCase().labels());
        }
        return missing;
    }

    private List<EvalCaseResult> grounded() {
        return results.stream().filter(result -> result.evalCase().isGrounded()).toList();
    }

    /** The cases the page metrics apply to: those declaring expected pages. */
    private List<EvalCaseResult> pageApplicable() {
        return results.stream()
                .filter(result -> CitationMetrics.pageLabelsApplicable(result.evalCase().labels()))
                .toList();
    }

    /**
     * Mean over a slice, with {@code 0.0} for an empty slice.
     *
     * <p>Returning {@code 0.0} rather than {@code 1.0} matters: an empty case
     * set, or a dataset with no answerable cases, must not satisfy a minimum
     * threshold by having nothing to measure.
     */
    private static double mean(List<EvalCaseResult> results,
                              java.util.function.ToDoubleFunction<EvalCaseResult> extractor) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (EvalCaseResult result : results) {
            sum += extractor.applyAsDouble(result);
        }
        return sum / results.size();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "RAG evaluation '%s': %d cases, corpus %d documents, k=%d%n",
                datasetName, results.size(), corpus.size(), retrievalK));
        for (EvalCaseResult result : results) {
            EvalCase c = result.evalCase();
            String pageScores = CitationMetrics.pageLabelsApplicable(c.labels())
                    ? String.format(Locale.ROOT, " pagePrecision=%.2f pageRecall=%.2f",
                            result.pageCitationPrecision(), result.pageCitationRecall())
                    : " pagePrecision=- pageRecall=-";
            sb.append(String.format(Locale.ROOT,
                    "  [%s/%s] expected=%s pages=%s refusal=%s observedRefusal=%s "
                            + "retrieved=%d cited=%d "
                            + "RR=%.2f P@k=%.2f R@k=%.2f keywords=%.2f citationPrecision=%.2f%s%n",
                    c.id(),
                    c.category() == null ? "-" : c.category(),
                    c.resolvedDocuments().isEmpty() ? "-" : String.join("|", c.resolvedDocuments()),
                    c.expectedPages().isEmpty() ? "-" : c.expectedPages().toString(),
                    c.expectRefusal(),
                    result.observation().refusal(),
                    result.observation().retrieved().size(),
                    result.observation().citations().size(),
                    result.reciprocalRank(), result.precisionAtK(), result.recallAtK(),
                    result.keywordCoverage(), result.citationPrecision(), pageScores));
        }
        sb.append(String.format(Locale.ROOT,
                "  hitRate=%.2f  MRR=%.2f  precision@k=%.2f  recall@k=%.2f  "
                        + "keywordCoverage=%.2f  citationPrecision=%.2f  refusalCorrectness=%.2f%n",
                hitRate(), meanReciprocalRank(), meanPrecisionAtK(), meanRecallAtK(),
                meanKeywordCoverage(), meanCitationPrecision(), refusalCorrectness()));
        sb.append(String.format(Locale.ROOT,
                "  pageCitationPrecision=%.2f  pageCitationRecall=%.2f  "
                        + "(page-applicable cases %d/%d, missing-page citations %d)%n",
                meanPageCitationPrecision(), meanPageCitationRecall(),
                pageApplicableCaseCount(), results.size(), missingPageCitationCount()));
        return sb.toString();
    }
}
