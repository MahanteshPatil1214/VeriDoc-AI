package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Document-level citation precision.
 *
 * <p>These tests use the <em>citation</em> list only. Retrieval is not involved:
 * a case can cite correctly and still have retrieved nothing, which is asserted
 * in {@link RagEvaluatorTest}.
 */
class CitationMetricsTest {

    private static final RelevanceLabels A = RelevanceLabels.document("a.pdf");
    private static final RelevanceLabels AB = RelevanceLabels.of(List.of("a.pdf", "b.pdf"), null);

    private static EvidencePassage cited(String document, Integer page, int seed) {
        return new EvidencePassage(document,
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(seed)), page);
    }

    @Test
    @DisplayName("every citation from an expected document scores 1.0")
    void allCitationsMatch() {
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 1, 1), cited("a.pdf", 2, 2)), A))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("mixed citations score the relevant fraction")
    void mixedCitations() {
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 1, 1), cited("z.pdf", 1, 2)), A))
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("citations from no expected document score 0.0")
    void noCitationMatches() {
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("z.pdf", 1, 1)), A))
                .isZero();
    }

    @Test
    @DisplayName("a grounded case that cites nothing scores 0.0, never a vacuous 1.0")
    void emptyCitationsWithLabelsIsNotFree() {
        assertThat(CitationMetrics.citationPrecision(List.of(), A)).isZero();
    }

    @Test
    @DisplayName("no citations and no expectation scores 0.0 and is excluded from aggregates")
    void emptyCitationsWithoutLabels() {
        assertThat(CitationMetrics.citationPrecision(List.of(), RelevanceLabels.none())).isZero();
    }

    @Test
    @DisplayName("citing something when nothing was expected scores 0.0")
    void citationsWithoutLabels() {
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 1, 1)), RelevanceLabels.none()))
                .isZero();
    }

    @Test
    @DisplayName("multi-document expectations accept a citation from any expected document")
    void multiDocumentExpectation() {
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 1, 1), cited("b.pdf", 1, 2)), AB))
                .isEqualTo(1.0);
        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 1, 1), cited("c.pdf", 1, 2)), AB))
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("precision is document-level: page labels do not make a document citation wrong")
    void documentLevelIgnoresPages() {
        RelevanceLabels pageFive = RelevanceLabels.of(List.of("a.pdf"), List.of(5));

        assertThat(CitationMetrics.citationPrecision(
                List.of(cited("a.pdf", 2, 1)), pageFive))
                .isEqualTo(1.0);
    }
}
