package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Page-level citation precision and recall.
 *
 * <p>These tests use the <em>citation</em> list only. The page metrics sit one
 * level below the document-level metric in {@link CitationMetrics}: a citation
 * to the right document is still wrong when it points at the wrong page.
 *
 * <p>The eight edge cases pinned here are the ones that decide whether the
 * metric is meaningful or merely decorative: applicability, empty citations,
 * citations that miss the expected document entirely, wrong pages, missing page
 * metadata, wrong-document citations, duplicates, and a mixed correct/incorrect
 * pair.
 */
class PageCitationMetricsTest {

    /** One expected document, one expected page. */
    private static final RelevanceLabels PAGE_ONE = RelevanceLabels.of(List.of("a.pdf"), List.of(1));

    /** One expected document, two expected pages. */
    private static final RelevanceLabels PAGES_ONE_AND_TWO =
            RelevanceLabels.of(List.of("a.pdf"), List.of(1, 2));

    /** One expected document, no page label: the page metrics do not apply. */
    private static final RelevanceLabels DOCUMENT_ONLY = RelevanceLabels.document("a.pdf");

    private static EvidencePassage cited(String document, Integer page, int seed) {
        return new EvidencePassage(document,
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(seed)), page);
    }

    // ---------------------------------------------------------------------
    // Edge case 1 - no page expectations
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 1: a case without page expectations is not applicable to the page metrics")
    void noPageExpectationIsNotApplicable() {
        assertThat(CitationMetrics.pageLabelsApplicable(DOCUMENT_ONLY)).isFalse();
        assertThat(CitationMetrics.pageLabelsApplicable(RelevanceLabels.none())).isFalse();
        assertThat(CitationMetrics.pageLabelsApplicable(PAGE_ONE)).isTrue();
    }

    @Test
    @DisplayName("EDGE 1: a non-applicable case scores 0.0 rather than a vacuous 1.0")
    void nonApplicableCaseScoresZero() {
        // The citation is on the expected document, so the document metric is
        // satisfied; the page metric has nothing to measure and must not look
        // perfect.
        assertThat(CitationMetrics.citationPrecision(List.of(cited("a.pdf", 9, 1)), DOCUMENT_ONLY))
                .isEqualTo(1.0);
        assertThat(CitationMetrics.pageCitationPrecision(List.of(cited("a.pdf", 9, 1)), DOCUMENT_ONLY))
                .isZero();
        assertThat(CitationMetrics.pageCitationRecall(List.of(cited("a.pdf", 9, 1)), DOCUMENT_ONLY))
                .isZero();
        assertThat(CitationMetrics.missingPageCitationCount(List.of(cited("a.pdf", null, 1)), DOCUMENT_ONLY))
                .isZero();
    }

    @Test
    @DisplayName("EDGE 1: non-applicable cases are excluded from the page aggregates")
    void nonApplicableCasesAreExcludedFromAggregates() {
        EvalCase pageCase = new EvalCase("p", "direct", "question", null,
                List.of("a.pdf"), List.of(1), List.of("answer"), List.of("claim"),
                false, null, List.of(), false, List.of());
        EvalCase documentOnlyCase = new EvalCase("d", "direct", "question", null,
                List.of("a.pdf"), List.of(), List.of("answer"), List.of("claim"),
                false, null, List.of(), false, List.of());

        EvalCaseResult pageResult = RagEvaluator.grade(pageCase,
                new EvalObservation(List.of(), List.of(cited("a.pdf", 1, 1)), false, "answer [1]"), 8);
        // Correct document, wrong page, no page label: the page metrics cannot
        // apply, so this case must not drag the aggregate down to 0.5.
        EvalCaseResult documentOnlyResult = RagEvaluator.grade(documentOnlyCase,
                new EvalObservation(List.of(), List.of(cited("a.pdf", 9, 2)), false, "answer [1]"), 8);

        EvalReport report = new EvalReport("aggregate", List.of(),
                List.of(pageResult, documentOnlyResult), 8);

        assertThat(report.pageApplicableCaseCount()).isEqualTo(1);
        assertThat(report.meanPageCitationPrecision()).isEqualTo(1.0);
        assertThat(report.meanPageCitationRecall()).isEqualTo(1.0);
        assertThat(documentOnlyResult.pageCitationPrecision()).isZero();
        assertThat(documentOnlyResult.pageCitationRecall()).isZero();
    }

    // ---------------------------------------------------------------------
    // Edge case 2 - expected pages but no citations
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 2: expected pages but no citations score 0.0")
    void expectedPagesWithoutCitationsScoreZero() {
        assertThat(CitationMetrics.pageCitationPrecision(List.of(), PAGE_ONE)).isZero();
        assertThat(CitationMetrics.pageCitationRecall(List.of(), PAGE_ONE)).isZero();
        assertThat(CitationMetrics.pageCitationPrecision(null, PAGE_ONE)).isZero();
        assertThat(CitationMetrics.pageCitationRecall(null, PAGE_ONE)).isZero();
    }

    // ---------------------------------------------------------------------
    // Edge case 3 - citations that miss the expected document
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 3: expected pages but no citation to an expected document scores 0.0")
    void expectedPagesWithoutExpectedDocumentCitationScoreZero() {
        assertThat(CitationMetrics.pageCitationPrecision(List.of(cited("z.pdf", 1, 1)), PAGE_ONE))
                .isZero();
        assertThat(CitationMetrics.pageCitationRecall(List.of(cited("z.pdf", 1, 1)), PAGE_ONE))
                .isZero();
    }

    // ---------------------------------------------------------------------
    // Edge case 4 - right document, wrong page
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 4: a citation to the expected document on an unlabelled page is incorrect")
    void wrongPageIsIncorrect() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", 2, 1));

        // The document-level metric is satisfied ...
        assertThat(CitationMetrics.citationPrecision(citations, PAGE_ONE)).isEqualTo(1.0);
        // ... but the page-level metric is not.
        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isZero();
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGE_ONE)).isZero();
    }

    // ---------------------------------------------------------------------
    // Edge case 5 - missing page number
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 5: a citation with no page number is incorrect and counted separately")
    void missingPageNumberIsIncorrectAndDiagnosed() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", null, 1));

        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isZero();
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGE_ONE)).isZero();
        assertThat(CitationMetrics.missingPageCitationCount(citations, PAGE_ONE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("EDGE 5: the missing-page diagnostic separates missing metadata from a wrong page")
    void missingPageDiagnosticDistinguishesFromWrongPage() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", null, 1), cited("a.pdf", 9, 2));

        assertThat(CitationMetrics.missingPageCitationCount(citations, PAGE_ONE)).isEqualTo(1L);
        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isZero();
    }

    // ---------------------------------------------------------------------
    // Edge case 6 - wrong-document citations
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 6: a citation to another document is excluded from the page denominator")
    void wrongDocumentCitationIsExcludedFromPageDenominator() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", 1, 1), cited("z.pdf", 1, 2));

        // Page precision grades only the citation to the expected document,
        // which is on the right page.
        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isEqualTo(1.0);
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGE_ONE)).isEqualTo(1.0);
        // The document-level metric still charges for the wrong document.
        assertThat(CitationMetrics.citationPrecision(citations, PAGE_ONE)).isEqualTo(0.5);
    }

    // ---------------------------------------------------------------------
    // Edge case 7 - duplicate citations
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 7: duplicate citations count as separate citation items")
    void duplicateCitationsCountIndividually() {
        List<EvidencePassage> citations =
                List.of(cited("a.pdf", 1, 1), cited("a.pdf", 1, 2), cited("a.pdf", 2, 3));

        // Three citation items to the expected document, one of them on the
        // wrong page: 2/3. Deduplicating the two correct citations would give
        // 1/2 instead, so this is what proves duplicates are counted.
        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isEqualTo(2.0 / 3.0);
        // Recall counts labelled targets, so duplicates cannot inflate it.
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGE_ONE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("EDGE 7: repeating a correct citation does not change its correctness")
    void duplicatedCorrectCitationStaysCorrect() {
        assertThat(CitationMetrics.pageCitationPrecision(
                List.of(cited("a.pdf", 1, 1), cited("a.pdf", 1, 2)), PAGE_ONE))
                .isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------
    // Edge case 8 - one correct page and one wrong page
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EDGE 8: a correct and an incorrect page affect precision and recall differently")
    void correctAndWrongPageDifferBetweenPrecisionAndRecall() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", 1, 1), cited("a.pdf", 2, 2));

        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGE_ONE)).isEqualTo(0.5);
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGE_ONE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("EDGE 8: a missing labelled page lowers recall but not precision")
    void missedLabelledPageLowersRecallOnly() {
        List<EvidencePassage> citations = List.of(cited("a.pdf", 1, 1));

        assertThat(CitationMetrics.pageCitationPrecision(citations, PAGES_ONE_AND_TWO))
                .isEqualTo(1.0);
        assertThat(CitationMetrics.pageCitationRecall(citations, PAGES_ONE_AND_TWO))
                .isEqualTo(0.5);
    }
}
