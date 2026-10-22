package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-metric unit tests for retrieval.
 *
 * <p>Every input here is a <em>retrieved</em> list. No test constructs a
 * citation list, because citations must not be able to satisfy a retrieval
 * metric — that regression is asserted in {@link RagEvaluatorTest}.
 */
class RetrievalMetricsTest {

    private static final RelevanceLabels A = RelevanceLabels.document("a.pdf");
    private static final RelevanceLabels AB = RelevanceLabels.of(List.of("a.pdf", "b.pdf"), null);
    private static final RelevanceLabels A_PAGE_3 =
            RelevanceLabels.of(List.of("a.pdf"), List.of(3));

    private static EvidencePassage passage(String document, Integer page, int seed) {
        return new EvidencePassage(document,
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(seed)), page);
    }

    @Test
    @DisplayName("a relevant passage at rank 2 gives MRR 0.5 and full recall in a long window")
    void relevantAtSecondRank() {
        List<EvidencePassage> retrieved = List.of(
                passage("other.pdf", 1, 1),
                passage("a.pdf", 1, 2));

        assertThat(RetrievalMetrics.hitAtK(retrieved, A, 3)).isTrue();
        assertThat(RetrievalMetrics.reciprocalRank(retrieved, A)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 3)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 3)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("relevance is binary: an irrelevant rank-1 passage does not score")
    void irrelevantOnly() {
        List<EvidencePassage> retrieved = List.of(
                passage("x.pdf", 1, 1),
                passage("y.pdf", 1, 2));

        assertThat(RetrievalMetrics.hitAtK(retrieved, A, 8)).isFalse();
        assertThat(RetrievalMetrics.reciprocalRank(retrieved, A)).isZero();
        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 8)).isZero();
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 8)).isZero();
    }

    @Test
    @DisplayName("empty retrieved evidence scores zero everywhere")
    void emptyRetrieved() {
        List<EvidencePassage> retrieved = List.of();

        assertThat(RetrievalMetrics.hitAtK(retrieved, A, 8)).isFalse();
        assertThat(RetrievalMetrics.reciprocalRank(retrieved, A)).isZero();
        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 8)).isZero();
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 8)).isZero();
    }

    @Test
    @DisplayName("a case with no labels has no relevant set and scores zero, not a free pass")
    void noLabels() {
        List<EvidencePassage> retrieved = List.of(passage("a.pdf", 1, 1));
        RelevanceLabels none = RelevanceLabels.none();

        assertThat(none.isEmpty()).isTrue();
        assertThat(RetrievalMetrics.hitAtK(retrieved, none, 8)).isFalse();
        assertThat(RetrievalMetrics.reciprocalRank(retrieved, none)).isZero();
        assertThat(RetrievalMetrics.recallAtK(retrieved, none, 8)).isZero();
        assertThat(RetrievalMetrics.precisionAtK(retrieved, none, 8)).isZero();
    }

    @Test
    @DisplayName("precision divides by items evaluated, not by k")
    void precisionDividesByEvaluatedCount() {
        List<EvidencePassage> retrieved = List.of(passage("a.pdf", 1, 1));

        // One relevant item out of one evaluated must be 1.0, not 1/8.
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 8)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("precision truncates at k and scores 0.0 when k is 0")
    void precisionTruncatesAtK() {
        List<EvidencePassage> retrieved = List.of(
                passage("x.pdf", 1, 1),
                passage("a.pdf", 1, 2));

        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 1)).isZero();
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 2)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.precisionAtK(retrieved, A, 0)).isZero();
    }

    @Test
    @DisplayName("recall truncates at k")
    void recallTruncatesAtK() {
        List<EvidencePassage> retrieved = List.of(
                passage("x.pdf", 1, 1),
                passage("a.pdf", 1, 2));

        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 1)).isZero();
        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 2)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(retrieved, A, 0)).isZero();
    }

    @Test
    @DisplayName("page labels narrow relevance to the labelled page")
    void pageLabelsNarrowRelevance() {
        assertThat(A_PAGE_3.matches(passage("a.pdf", 3, 1))).isTrue();
        assertThat(A_PAGE_3.matches(passage("a.pdf", 2, 2))).isFalse();
        assertThat(A_PAGE_3.matches(passage("a.pdf", null, 3))).isFalse();
        assertThat(A_PAGE_3.matches(passage("b.pdf", 3, 4))).isFalse();
        assertThat(A.matches(passage("a.pdf", 99, 5))).isTrue();

        assertThat(RetrievalMetrics.recallAtK(List.of(passage("a.pdf", 2, 6)), A_PAGE_3, 8)).isZero();
        assertThat(RetrievalMetrics.recallAtK(List.of(passage("a.pdf", 3, 7)), A_PAGE_3, 8))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("multi-document recall is fractional and order-independent")
    void multiDocumentRecall() {
        assertThat(RetrievalMetrics.recallAtK(List.of(passage("a.pdf", 1, 1)), AB, 8))
                .isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(
                List.of(passage("b.pdf", 1, 2), passage("a.pdf", 1, 3)), AB, 8))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("MRR uses the first relevant retrieved item, not the best label")
    void mrrUsesFirstRelevantItem() {
        List<EvidencePassage> retrieved = List.of(
                passage("x.pdf", 1, 1),
                passage("b.pdf", 1, 2),
                passage("a.pdf", 1, 3));

        assertThat(RetrievalMetrics.reciprocalRank(retrieved, AB)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("hit@k is exactly recall@k > 0 and is not independent evidence")
    void hitAgreesWithRecall() {
        List<List<EvidencePassage>> samples = List.of(
                List.of(),
                List.of(passage("x.pdf", 1, 1)),
                List.of(passage("a.pdf", 1, 2)));

        for (List<EvidencePassage> retrieved : samples) {
            assertThat(RetrievalMetrics.hitAtK(retrieved, A, 8))
                    .isEqualTo(RetrievalMetrics.recallAtK(retrieved, A, 8) > 0.0);
        }
    }

    @Test
    @DisplayName("duplicate chunks do not inflate target-level recall but do count for precision")
    void duplicateChunks() {
        List<EvidencePassage> retrieved = List.of(
                passage("a.pdf", 1, 1),
                passage("a.pdf", 1, 2));

        assertThat(RetrievalMetrics.recallAtK(retrieved, AB, 8)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.precisionAtK(retrieved, AB, 2)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("page targets are counted one per labelled (document, page) pair")
    void pageTargetsAreExplicit() {
        RelevanceLabels both = RelevanceLabels.of(List.of("a.pdf", "b.pdf"), List.of(2, 3));

        assertThat(both.targets()).hasSize(4);
        assertThat(RetrievalMetrics.recallAtK(
                List.of(passage("a.pdf", 2, 1), passage("b.pdf", 3, 2)), both, 8))
                .isCloseTo(0.5, within(1e-9));
    }
}
