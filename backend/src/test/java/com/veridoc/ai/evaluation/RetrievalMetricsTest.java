package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure-metric unit tests for the RAG evaluation harness. */
class RetrievalMetricsTest {

    @Test
    @DisplayName("hit, reciprocal rank and recall reflect the rank of the expected document")
    void rankingMetrics() {
        List<String> ranked = List.of("a.pdf", "b.pdf", "c.pdf");

        assertThat(RetrievalMetrics.hit(ranked, "b.pdf")).isTrue();
        assertThat(RetrievalMetrics.hit(ranked, "z.pdf")).isFalse();
        assertThat(RetrievalMetrics.reciprocalRank(ranked, "a.pdf")).isEqualTo(1.0);
        assertThat(RetrievalMetrics.reciprocalRank(ranked, "c.pdf")).isCloseTo(1.0 / 3, within());
        assertThat(RetrievalMetrics.reciprocalRank(ranked, "z.pdf")).isZero();
        assertThat(RetrievalMetrics.recallAtK(ranked, "b.pdf", 2)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(ranked, "c.pdf", 2)).isZero();
    }

    @Test
    @DisplayName("precision@k counts distinct documents in the window over k")
    void precision() {
        List<String> ranked = List.of("a.pdf", "a.pdf", "b.pdf");
        assertThat(RetrievalMetrics.precisionAtK(ranked, 2)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.precisionAtK(ranked, 3)).isCloseTo(2.0 / 3, within());
        assertThat(RetrievalMetrics.precisionAtK(List.of(), 3)).isZero();
    }

    @Test
    @DisplayName("keyword coverage is the fraction of expected keywords present")
    void keywordCoverage() {
        assertThat(AnswerMetrics.keywordCoverage("The Vacation policy and ACCRUAL rules",
                List.of("vacation", "accrual", "leave"))).isCloseTo(2.0 / 3, within());
        assertThat(AnswerMetrics.keywordCoverage("anything", List.of())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("citation accuracy is the fraction of citations from the expected document")
    void citationAccuracy() {
        assertThat(CitationMetrics.citationAccuracy(List.of("a.pdf", "a.pdf"), "a.pdf"))
                .isEqualTo(1.0);
        assertThat(CitationMetrics.citationAccuracy(List.of("a.pdf", "b.pdf"), "a.pdf"))
                .isEqualTo(0.5);
        assertThat(CitationMetrics.citationAccuracy(List.of(), "a.pdf")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("evaluate grades cases and assertMeets throws with the report attached")
    void evaluateAndGate() throws Exception {
        EvalDataset dataset = new EvalDataset("unit", List.of(),
                List.of(new EvalCase("c1", "q", "a.pdf", List.of("grounded"), false)));
        EvalReport report = RagEvaluator.evaluate(dataset,
                c -> new EvalObservation(List.of("a.pdf"), false, "grounded answer"), 8);

        assertThat(report.hitRate()).isEqualTo(1.0);
        RagEvaluator.assertMeets(report, EvalThresholds.core());

        EvalReport bad = RagEvaluator.evaluate(dataset,
                c -> EvalObservation.refused("no"), 8);
        assertThatThrownBy(() -> RagEvaluator.assertMeets(bad, EvalThresholds.core()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("hitRate");
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
