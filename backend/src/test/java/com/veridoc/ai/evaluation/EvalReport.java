package com.veridoc.ai.evaluation;

import java.util.List;
import java.util.Locale;

import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;

/** Aggregated outcome of an evaluation run, plus a human-readable summary. */
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

    /** Fraction of grounded cases whose expected document was retrieved at all. */
    public double hitRate() {
        List<EvalCaseResult> grounded = grounded();
        return mean(grounded, result -> result.hit() ? 1.0 : 0.0);
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

    /** Fraction of all cases whose refusal behaviour matched expectation. */
    public double refusalCorrectness() {
        return mean(results, result -> result.refusalCorrect() ? 1.0 : 0.0);
    }

    private List<EvalCaseResult> grounded() {
        return results.stream().filter(result -> result.evalCase().isGrounded()).toList();
    }

    private static double mean(List<EvalCaseResult> results,
                              java.util.function.ToDoubleFunction<EvalCaseResult> extractor) {
        if (results.isEmpty()) {
            return 1.0;
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
            sb.append(String.format(Locale.ROOT,
                    "  [%s] expected=%s refusal=%s observedRefusal=%s RR=%.2f keywords=%.2f citations=%.2f%n",
                    c.id(),
                    c.expectedDocument() == null ? "-" : c.expectedDocument(),
                    c.expectRefusal(),
                    result.observation().refusal(),
                    result.reciprocalRank(), result.keywordCoverage(), result.citationAccuracy()));
        }
        sb.append(String.format(Locale.ROOT,
                "  hitRate=%.2f  MRR=%.2f  precision@k=%.2f  recall@k=%.2f  keywordCoverage=%.2f  refusalCorrectness=%.2f%n",
                hitRate(), meanReciprocalRank(), meanPrecisionAtK(), meanRecallAtK(),
                meanKeywordCoverage(), refusalCorrectness()));
        return sb.toString();
    }
}
