package com.veridoc.ai.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every case in a dataset through a {@link CaseRunner} and grades the
 * outcome. Deliberately free of Spring so it can be unit-tested and reused with
 * any transport (HTTP, service call, ...).
 *
 * <p>Retrieval metrics are fed {@link EvalObservation#retrieved()}; citation
 * metrics are fed {@link EvalObservation#citations()}. Keeping those two
 * arguments distinct is the whole point of this class's grading step.
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

    /** Package-visible so the metric wiring can be unit-tested without a transport. */
    static EvalCaseResult grade(EvalCase evalCase, EvalObservation observation,
                                int retrievalK) {
        RelevanceLabels labels = evalCase.labels();
        List<EvidencePassage> retrieved = observation.retrieved();
        return new EvalCaseResult(
                evalCase,
                observation,
                RetrievalMetrics.hitAtK(retrieved, labels, retrievalK),
                RetrievalMetrics.reciprocalRank(retrieved, labels),
                RetrievalMetrics.precisionAtK(retrieved, labels, retrievalK),
                RetrievalMetrics.recallAtK(retrieved, labels, retrievalK),
                AnswerMetrics.keywordCoverage(observation.answer(), evalCase.expectedKeywords()),
                observation.refusal() == evalCase.expectRefusal(),
                CitationMetrics.citationPrecision(observation.citations(), labels),
                CitationMetrics.pageCitationPrecision(observation.citations(), labels),
                CitationMetrics.pageCitationRecall(observation.citations(), labels));
    }

    /**
     * Throws a descriptive {@link AssertionError} when the report misses the
     * deterministic-double gate.
     *
     * <p>This is the Stage 2 gate: every metric the double can express is
     * checked, including recall, precision and citation precision. The Stage 3
     * real-provider gate is {@link EvalThresholds#core()}.
     */
    public static void assertMeets(EvalReport report, EvalGate gate) {
        List<String> failures = new ArrayList<>();
        check(failures, "hitRate", report.hitRate(), gate.minHitRate());
        check(failures, "meanReciprocalRank", report.meanReciprocalRank(),
                gate.minMeanReciprocalRank());
        check(failures, "meanRecallAtK", report.meanRecallAtK(), gate.minMeanRecallAtK());
        check(failures, "meanPrecisionAtK", report.meanPrecisionAtK(), gate.minMeanPrecisionAtK());
        check(failures, "meanKeywordCoverage", report.meanKeywordCoverage(),
                gate.minMeanKeywordCoverage());
        check(failures, "meanCitationPrecision", report.meanCitationPrecision(),
                gate.minMeanCitationPrecision());
        check(failures, "refusalCorrectness", report.refusalCorrectness(),
                gate.minRefusalCorrectness());
        if (!failures.isEmpty()) {
            throw new AssertionError("RAG evaluation below the deterministic-stub gate: "
                    + String.join("; ", failures) + System.lineSeparator() + report.summary());
        }
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
