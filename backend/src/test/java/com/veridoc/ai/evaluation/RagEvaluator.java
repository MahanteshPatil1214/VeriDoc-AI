package com.veridoc.ai.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every case in a dataset through a {@link CaseRunner} and grades the
 * outcome. Deliberately free of Spring so it can be unit-tested and reused with
 * any transport (HTTP, service call, ...).
 */
public final class RagEvaluator {

    /** Produces one observation for a case. May throw whatever the transport does. */
    @FunctionalInterface
    public interface CaseRunner {
        EvalObservation run(EvalCase evalCase) throws Exception;
    }

    private RagEvaluator() {
    }

    public static EvalReport evaluate(EvalDataset dataset, CaseRunner runner,
                                      int retrievalK) throws Exception {
        List<EvalCaseResult> results = new ArrayList<>(dataset.cases().size());
        for (EvalCase evalCase : dataset.cases()) {
            EvalObservation observation = runner.run(evalCase);
            results.add(grade(evalCase, observation, retrievalK));
        }
        return new EvalReport(dataset.name(), dataset.corpus(), results, retrievalK);
    }

    private static EvalCaseResult grade(EvalCase evalCase, EvalObservation observation,
                                        int retrievalK) {
        List<String> ranked = observation.rankedDocuments();
        String expected = evalCase.expectedDocument();
        boolean refusalCorrect = observation.refusal() == evalCase.expectRefusal();
        return new EvalCaseResult(
                evalCase,
                observation,
                RetrievalMetrics.hit(ranked, expected),
                RetrievalMetrics.reciprocalRank(ranked, expected),
                RetrievalMetrics.precisionAtK(ranked, retrievalK),
                RetrievalMetrics.recallAtK(ranked, expected, retrievalK),
                AnswerMetrics.keywordCoverage(observation.answer(), evalCase.expectedKeywords()),
                refusalCorrect,
                CitationMetrics.citationAccuracy(ranked, expected));
    }

    /** Throws a descriptive {@link AssertionError} when the report misses the gate. */
    public static void assertMeets(EvalReport report, EvalThresholds thresholds) {
        List<String> failures = new ArrayList<>();
        check(failures, "hitRate", report.hitRate(), thresholds.minHitRate());
        check(failures, "meanReciprocalRank", report.meanReciprocalRank(),
                thresholds.minMeanReciprocalRank());
        check(failures, "meanKeywordCoverage", report.meanKeywordCoverage(),
                thresholds.minMeanKeywordCoverage());
        check(failures, "refusalCorrectness", report.refusalCorrectness(),
                thresholds.minRefusalCorrectness());
        if (!failures.isEmpty()) {
            throw new AssertionError("RAG evaluation below threshold: "
                    + String.join("; ", failures) + System.lineSeparator() + report.summary());
        }
    }

    private static void check(List<String> failures, String metric, double actual, double minimum) {
        if (actual + 1e-9 < minimum) {
            failures.add(String.format(java.util.Locale.ROOT, "%s=%.2f < %.2f",
                    metric, actual, minimum));
        }
    }
}
