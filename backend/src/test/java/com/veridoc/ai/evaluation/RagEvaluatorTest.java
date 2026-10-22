package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring tests for {@link RagEvaluator}: they prove that retrieval metrics read
 * the <em>retrieved</em> list and citation metrics read the <em>cited</em> list,
 * and that the two can never stand in for one another.
 */
class RagEvaluatorTest {

    private static EvidencePassage passage(String document, Integer page, int seed) {
        return new EvidencePassage(document,
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(seed)), page);
    }

    private static EvalCase grounded(String id, String document) {
        return new EvalCase(id, "direct", "question " + id, null, List.of(document),
                List.of(), List.of("answer"), List.of("test claim"), false, null, List.of(),
                false, List.of());
    }

    private static EvalCase refusal(String id) {
        return new EvalCase(id, "refusal", "question " + id, null, List.of(), List.of(),
                List.of(), List.of(), true, "near miss", List.of(), false, List.of());
    }

    @Test
    @DisplayName("REGRESSION: citing the expected document cannot make retrieval pass")
    void citationCannotSatisfyRetrievalMetrics() throws Exception {
        EvalDataset dataset = new EvalDataset("regression", List.of(),
                List.of(grounded("c1", "a.pdf")));
        EvalObservation observed = new EvalObservation(
                List.of(),                                   // retrieval returned nothing
                List.of(passage("a.pdf", 1, 1)),             // yet the answer cites a.pdf
                false,
                "a.pdf says so [1]");

        EvalReport report = RagEvaluator.evaluate(dataset, evalCase -> observed, 8);

        // Citation metrics see the citation and score it as correct ...
        assertThat(report.meanCitationPrecision()).isEqualTo(1.0);
        // ... but retrieval metrics are blind to it and score zero.
        assertThat(report.hitRate()).isZero();
        assertThat(report.meanRecallAtK()).isZero();
        assertThat(report.meanReciprocalRank()).isZero();
        assertThat(report.meanPrecisionAtK()).isZero();

        // And the gate still fails on retrieval, so the citation cannot mask it.
        assertThatThrownBy(() -> RagEvaluator.assertMeets(report, EvalThresholds.core()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("hitRate");
    }

    @Test
    @DisplayName("a grounded case is graded from retrieved order and actual citations")
    void groundedCaseIsGradedFromBothLists() {
        EvalCase evalCase = grounded("c1", "a.pdf");
        EvalObservation observed = new EvalObservation(
                List.of(passage("x.pdf", 1, 1), passage("a.pdf", 1, 2)),
                List.of(passage("a.pdf", 1, 2)),
                false,
                "answer [1]");

        EvalCaseResult result = RagEvaluator.grade(evalCase, observed, 2);

        assertThat(result.hit()).isTrue();
        assertThat(result.reciprocalRank()).isEqualTo(0.5);
        assertThat(result.precisionAtK()).isEqualTo(0.5);
        assertThat(result.recallAtK()).isEqualTo(1.0);
        assertThat(result.keywordCoverage()).isEqualTo(1.0);
        assertThat(result.refusalCorrect()).isTrue();
        assertThat(result.citationPrecision()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retrieval aggregates ignore abstention cases instead of grading them as zero")
    void abstentionExcludedFromRetrievalAggregates() throws Exception {
        EvalDataset dataset = new EvalDataset("mixed", List.of(),
                List.of(grounded("g", "a.pdf"), refusal("r")));
        Map<String, EvalObservation> observations = Map.of(
                "g", new EvalObservation(
                        List.of(passage("a.pdf", 1, 1)),
                        List.of(passage("a.pdf", 1, 2)),
                        false, "answer [1]"),
                "r", EvalObservation.refused("I don't know."));

        EvalReport report = RagEvaluator.evaluate(dataset,
                evalCase -> observations.get(evalCase.id()), 8);

        // One grounded hit out of one grounded case, not one out of two cases.
        assertThat(report.hitRate()).isEqualTo(1.0);
        assertThat(report.meanRecallAtK()).isEqualTo(1.0);
        assertThat(report.meanReciprocalRank()).isEqualTo(1.0);
        assertThat(report.meanCitationPrecision()).isEqualTo(1.0);
        assertThat(report.refusalCorrectness()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("refusal correctness is scored in both directions")
    void refusalCorrectnessBothDirections() {
        EvalCase groundedCase = grounded("g", "a.pdf");
        EvalCase refusalCase = refusal("r");

        assertThat(RagEvaluator.grade(groundedCase, EvalObservation.refused("no"), 8)
                .refusalCorrect()).isFalse();
        assertThat(RagEvaluator.grade(groundedCase,
                new EvalObservation(List.of(), List.of(), false, "answer"), 8)
                .refusalCorrect()).isTrue();
        assertThat(RagEvaluator.grade(refusalCase, EvalObservation.refused("no"), 8)
                .refusalCorrect()).isTrue();
    }

    @Test
    @DisplayName("an empty report scores zero everywhere and cannot clear the gate")
    void emptyReportCannotClearTheGate() {
        EvalReport empty = new EvalReport("empty", List.of(), List.of(), 8);

        assertThat(empty.hitRate()).isZero();
        assertThat(empty.meanRecallAtK()).isZero();
        assertThat(empty.meanReciprocalRank()).isZero();
        assertThat(empty.meanCitationPrecision()).isZero();
        assertThat(empty.refusalCorrectness()).isZero();

        assertThatThrownBy(() -> RagEvaluator.assertMeets(empty, EvalThresholds.core()))
                .isInstanceOf(AssertionError.class);
    }
}
