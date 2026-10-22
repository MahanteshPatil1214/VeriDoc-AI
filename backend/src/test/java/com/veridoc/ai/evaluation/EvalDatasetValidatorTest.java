package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;
import com.veridoc.ai.evaluation.EvalDatasetValidator.Violation;

/**
 * Unit tests for the deterministic dataset validator.
 *
 * <p>Every invariant the validator enforces has its own test, and every test
 * asserts <em>which</em> case and <em>which</em> field the violation is
 * reported against — a validator that only says "invalid dataset" is useless
 * when a 42-case file has to be repaired.
 */
class EvalDatasetValidatorTest {

    /** The chunker's minimum page size in the shipped configuration. */
    private static final int MIN_TOKENS = 40;

    private static final String PAGE_ONE = "The employee handbook records that every request must be reviewed by a "
            + "manager and written into the register before any approval is granted, so that each keyword in the "
            + "request can be traced back to the owner of the process and to the page that finally answers it.";

    private static final String PAGE_TWO = "The second page of the handbook describes how a claim is checked against "
            + "the source passage, which keyword proves that the answer is supported, and how a reviewer confirms "
            + "that the register entry is complete before the request is closed.";

    // ---------------------------------------------------------------- cases

    @Test
    @DisplayName("duplicate case ids are rejected")
    void duplicateCaseIdsAreRejected() {
        EvalCase first = new Case("dup").build();
        EvalCase second = new Case("dup").question("a different question").build();

        assertViolation(dataset(first, second), "dup", "id", "duplicate case id");
    }

    @Test
    @DisplayName("blank case ids are rejected")
    void blankCaseIdsAreRejected() {
        assertViolation(dataset(new Case("  ").build()), "<blank>", "id", "must not be blank");
    }

    @Test
    @DisplayName("duplicate questions are rejected")
    void duplicateQuestionsAreRejected() {
        EvalCase first = new Case("one").build();
        EvalCase second = new Case("two").build();

        assertViolation(dataset(first, second), "two", "question", "duplicate question");
    }

    @Test
    @DisplayName("an unknown corpus document is rejected")
    void unknownCorpusDocumentIsRejected() {
        EvalCase evalCase = new Case("ghost").expectedDocuments("not-in-corpus.pdf").build();

        assertViolation(dataset(evalCase), "ghost", "expectedDocuments", "unknown corpus document");
    }

    @Test
    @DisplayName("an answerable case without keywords is rejected")
    void answerableCaseWithoutKeywordsIsRejected() {
        EvalCase evalCase = new Case("no-keywords").expectedKeywords().build();

        assertViolation(dataset(evalCase), "no-keywords", "expectedKeywords", "at least one expected keyword");
    }

    @Test
    @DisplayName("an answerable case without claims is rejected")
    void answerableCaseWithoutClaimsIsRejected() {
        EvalCase evalCase = new Case("no-claims").expectedClaims().build();

        assertViolation(dataset(evalCase), "no-claims", "expectedClaims", "at least one expected claim");
    }

    @Test
    @DisplayName("a keyword that does not occur in the expected document is rejected")
    void keywordOutsideTheExpectedDocumentIsRejected() {
        EvalCase evalCase = new Case("stray-keyword").expectedKeywords("quokka registry")
                .build();

        assertViolation(dataset(evalCase), "stray-keyword", "expectedKeywords",
                "does not occur in any expected document");
    }

    @Test
    @DisplayName("a refusal case without a near-miss explanation is rejected")
    void refusalCaseWithoutNearMissIsRejected() {
        EvalCase evalCase = new Case("no-near-miss").category("refusal").expectRefusal(true)
                .expectedDocuments().expectedKeywords().expectedClaims().nearMiss(null).build();

        assertViolation(dataset(evalCase), "no-near-miss", "nearMiss", "must explain");
    }

    @Test
    @DisplayName("a refusal case may not declare expected documents")
    void refusalCaseWithExpectedDocumentIsRejected() {
        EvalCase evalCase = new Case("refusal-with-doc").category("refusal").expectRefusal(true)
                .expectedKeywords().expectedClaims().nearMiss("a handbook that does not cover it").build();

        assertViolation(dataset(evalCase), "refusal-with-doc", "expectedDocuments",
                "must not declare expected documents");
    }

    @Test
    @DisplayName("an answerable case may not declare a near-miss explanation")
    void answerableCaseWithNearMissIsRejected() {
        EvalCase evalCase = new Case("answer-with-near-miss")
                .nearMiss("some other page").build();

        assertViolation(dataset(evalCase), "answer-with-near-miss", "nearMiss",
                "must not declare a near-miss");
    }

    @Test
    @DisplayName("a page label below one is rejected")
    void pageLabelBelowOneIsRejected() {
        EvalCase evalCase = new Case("page-zero").expectedPages(0).build();

        assertViolation(dataset(evalCase), "page-zero", "expectedPages[0]", "page must be >= 1");
    }

    @Test
    @DisplayName("a page label beyond the document is rejected")
    void pageLabelBeyondTheDocumentIsRejected() {
        EvalCase evalCase = new Case("page-too-high").expectedPages(9).build();

        assertViolation(dataset(evalCase), "page-too-high", "expectedPages[0]", "exceeds the page count");
    }

    @Test
    @DisplayName("page labels require exactly one expected document")
    void pageLabelsRequireASingleDocument() {
        EvalCase evalCase = new Case("two-docs-one-page").expectedDocuments("handbook.pdf", "other.pdf")
                .expectedPages(1).build();

        assertViolation(dataset(evalCase), "two-docs-one-page", "expectedPages",
                "exactly one expected document");
    }

    @Test
    @DisplayName("a duplicated page label is rejected")
    void duplicatePageLabelIsRejected() {
        EvalCase evalCase = new Case("dup-page").expectedPages(1, 1).build();

        assertViolation(dataset(evalCase), "dup-page", "expectedPages[1]", "duplicate page label");
    }

    @Test
    @DisplayName("a non replayable history role is rejected")
    void historyRoleMustBeReplayable() {
        EvalCase evalCase = new Case("assistant-turn").category("multi-turn")
                .history(new HistoryTurn(HistoryTurn.ASSISTANT, "a prior reply")).build();

        assertViolation(dataset(evalCase), "assistant-turn", "history[0].role", "not replayable");
    }

    @Test
    @DisplayName("a blank history entry is rejected")
    void blankHistoryContentIsRejected() {
        EvalCase evalCase = new Case("blank-turn").category("multi-turn")
                .history(new HistoryTurn(HistoryTurn.USER, "   ")).build();

        assertViolation(dataset(evalCase), "blank-turn", "history[0].content", "must not be blank");
    }

    @Test
    @DisplayName("only multi-turn cases may declare history")
    void historyIsOnlyAllowedOnMultiTurnCases() {
        EvalCase evalCase = new Case("leaky").history(HistoryTurn.user("a prior question")).build();

        assertViolation(dataset(evalCase), "leaky", "history", "only multi-turn cases");
    }

    @Test
    @DisplayName("a multi-turn case must declare history")
    void multiTurnCaseMustDeclareHistory() {
        EvalCase evalCase = new Case("no-turns").category("multi-turn").build();

        assertViolation(dataset(evalCase), "no-turns", "history", "at least one prior user turn");
    }

    @Test
    @DisplayName("the adversarial flag must match the category")
    void adversarialFlagMustMatchTheCategory() {
        EvalCase evalCase = new Case("flagged").category("direct").adversarial(true).build();

        assertViolation(dataset(evalCase), "flagged", "adversarial", "must use category");
    }

    @Test
    @DisplayName("a prompt-injection case must set the adversarial flag")
    void promptInjectionCaseMustSetTheAdversarialFlag() {
        EvalCase evalCase = new Case("injection").category("prompt-injection")
                .injectionPhrases("APPROVED").build();

        assertViolation(dataset(evalCase), "injection", "adversarial", "must set adversarial=true");
    }

    @Test
    @DisplayName("a prompt-injection case must list the phrases it forbids")
    void injectionPhrasesAreRequiredForPromptInjectionCases() {
        EvalCase evalCase = new Case("injection-no-phrases").category("prompt-injection")
                .adversarial(true).build();

        assertViolation(dataset(evalCase), "injection-no-phrases", "injectionPhrases",
                "must list the phrases");
    }

    @Test
    @DisplayName("only prompt-injection cases may declare injection phrases")
    void injectionPhrasesAreOnlyAllowedForPromptInjectionCases() {
        EvalCase evalCase = new Case("stray-phrases").injectionPhrases("APPROVED").build();

        assertViolation(dataset(evalCase), "stray-phrases", "injectionPhrases",
                "only prompt-injection cases");
    }

    @Test
    @DisplayName("an unknown category is rejected")
    void unknownCategoryIsRejected() {
        EvalCase evalCase = new Case("odd").category("vibes").build();

        assertViolation(dataset(evalCase), "odd", "category", "unknown category");
    }

    @Test
    @DisplayName("a multi-document case needs at least two documents")
    void multiDocumentCaseNeedsTwoDocuments() {
        EvalCase evalCase = new Case("one-doc").category("multi-document").build();

        assertViolation(dataset(evalCase), "one-doc", "expectedDocuments", "at least two documents");
    }

    @Test
    @DisplayName("a multi-document case needs one claim per source")
    void multiDocumentCaseNeedsTwoClaims() {
        EvalCase evalCase = new Case("one-claim").category("multi-document")
                .expectedDocuments("handbook.pdf", "other.pdf").build();

        assertViolation(dataset(evalCase), "one-claim", "expectedClaims", "at least two claims");
    }

    @Test
    @DisplayName("a multi-page case needs a page label")
    void multiPageCaseNeedsAPageLabel() {
        EvalCase evalCase = new Case("no-page").category("multi-page").build();

        assertViolation(dataset(evalCase), "no-page", "expectedPages", "must declare the expected page");
    }

    @Test
    @DisplayName("a multi-page case needs a document with at least two pages")
    void multiPageCaseNeedsAMultiPageDocument() {
        CorpusDocument singlePage = new CorpusDocument("single.pdf", null, List.of(PAGE_ONE));
        EvalCase evalCase = new Case("single-page-doc").category("multi-page").expectedPages(1)
                .expectedDocuments("single.pdf").build();

        EvalDataset dataset = new EvalDataset("test", List.of(singlePage), List.of(evalCase));

        assertViolation(dataset, "single-page-doc", "expectedDocuments", "at least two pages");
    }

    @Test
    @DisplayName("an exact-value case needs a claim with a literal value")
    void exactValueCaseNeedsALiteralValue() {
        EvalCase evalCase = new Case("no-value").category("exact-value")
                .expectedClaims("The policy states a duration.").build();

        assertViolation(dataset(evalCase), "no-value", "expectedClaims", "literal value");
    }

    @Test
    @DisplayName("the refusal category requires the refusal expectation")
    void refusalCategoryNeedsRefusalExpectation() {
        EvalCase evalCase = new Case("refusal-category").category("refusal").expectRefusal(false)
                .expectedDocuments().expectedKeywords().expectedClaims().nearMiss("a near miss").build();

        assertViolation(dataset(evalCase), "refusal-category", "expectRefusal", "requires expectRefusal=true");
    }

    @Test
    @DisplayName("a corpus page below the chunker minimum is rejected")
    void corpusPageBelowTheChunkerMinimumIsRejected() {
        CorpusDocument tooShort = new CorpusDocument("short.pdf", null, List.of("Only a handful of tokens here."));
        EvalCase evalCase = new Case("short").expectedDocuments("short.pdf")
                .expectedKeywords("handbook").build();

        EvalDataset dataset = new EvalDataset("test", List.of(tooShort), List.of(evalCase));

        assertViolation(dataset, "short.pdf", "pages[0]", "below the chunker minimum");
    }

    @Test
    @DisplayName("a corpus document may not set both text and pages")
    void corpusDocumentCannotSetBothTextAndPages() {
        CorpusDocument both = new CorpusDocument("both.pdf", PAGE_ONE, List.of(PAGE_TWO));

        EvalDataset dataset = new EvalDataset("test", List.of(both), List.of(new Case("c").build()));

        assertViolation(dataset, "both.pdf", "pages", "not both");
    }

    @Test
    @DisplayName("a blank corpus filename is rejected")
    void blankCorpusFilenameIsRejected() {
        CorpusDocument blank = new CorpusDocument("  ", null, List.of(PAGE_ONE));

        EvalDataset dataset = new EvalDataset("test", List.of(blank), List.of(new Case("c").build()));

        assertViolation(dataset, "<corpus>", "filename", "must not be blank");
    }

    @Test
    @DisplayName("a duplicated corpus filename is rejected")
    void duplicateCorpusFilenameIsRejected() {
        CorpusDocument first = new CorpusDocument("handbook.pdf", null, List.of(PAGE_ONE));
        CorpusDocument second = new CorpusDocument("handbook.pdf", null, List.of(PAGE_TWO));

        EvalDataset dataset = new EvalDataset("test", List.of(first, second), List.of(new Case("c").build()));

        assertViolation(dataset, "handbook.pdf", "filename", "duplicate corpus document filename");
    }

    @Test
    @DisplayName("a duplicated keyword entry is rejected")
    void duplicateKeywordEntryIsRejected() {
        EvalCase evalCase = new Case("dup-keyword").expectedKeywords("keyword", "Keyword").build();

        assertViolation(dataset(evalCase), "dup-keyword", "expectedKeywords[1]", "duplicate entry");
    }

    @Test
    @DisplayName("a dataset without a corpus is rejected")
    void datasetWithoutCorpusIsRejected() {
        EvalDataset dataset = new EvalDataset("test", List.of(), List.of(new Case("c").build()));

        assertViolation(dataset, "<dataset>", "corpus", "corpus must not be empty");
    }

    @Test
    @DisplayName("a dataset without a name is rejected")
    void blankDatasetNameIsRejected() {
        EvalDataset dataset = new EvalDataset(" ", List.of(corpusDocument()), List.of(new Case("c").build()));

        assertViolation(dataset, "<dataset>", "name", "dataset name must not be blank");
    }

    @Test
    @DisplayName("every violation is reported, not only the first")
    void everyViolationIsReported() {
        EvalCase first = new Case("same").expectedKeywords().build();
        EvalCase second = new Case("same").expectedKeywords().build();

        List<Violation> violations = EvalDatasetValidator.violations(dataset(first, second), MIN_TOKENS);

        assertThat(violations).hasSizeGreaterThan(1);
        assertThat(violations).anyMatch(v -> v.caseId().equals("same") && v.field().equals("id"));
        assertThat(violations).anyMatch(v -> v.caseId().equals("same")
                && v.field().equals("expectedKeywords"));
    }

    @Test
    @DisplayName("the shipped dataset passes validation")
    void theShippedDatasetIsValid() {
        EvalDataset dataset = EvalDatasetLoader.load("/rag-eval/eval-dataset.json");

        assertThat(EvalDatasetValidator.violations(dataset, MIN_TOKENS)).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private static void assertViolation(EvalDataset dataset, String caseId, String field,
                                        String messageFragment) {
        List<Violation> violations = EvalDatasetValidator.violations(dataset, MIN_TOKENS);
        assertThat(violations)
                .as("violations for case '%s' field '%s'", caseId, field)
                .anyMatch(violation -> violation.caseId().equals(caseId)
                        && violation.field().equals(field)
                        && violation.message().toLowerCase(java.util.Locale.ROOT)
                        .contains(messageFragment.toLowerCase(java.util.Locale.ROOT)));
    }

    private static EvalDataset dataset(EvalCase... cases) {
        return new EvalDataset("test", List.of(corpusDocument(), secondDocument()), List.of(cases));
    }

    private static CorpusDocument corpusDocument() {
        return new CorpusDocument("handbook.pdf", null, List.of(PAGE_ONE, PAGE_TWO));
    }

    private static CorpusDocument secondDocument() {
        return new CorpusDocument("other.pdf", null, List.of(PAGE_ONE, PAGE_TWO));
    }

    /** Small fluent builder so every test states only what it is about. */
    private static final class Case {

        private String id = "case";
        private String category = "direct";
        private String question = "How is a request reviewed?";
        private String expectedDocument;
        private List<String> expectedDocuments = List.of("handbook.pdf");
        private List<Integer> expectedPages = List.of();
        private List<String> expectedKeywords = List.of("keyword");
        private List<String> expectedClaims = List.of("A request is reviewed by a manager.");
        private boolean expectRefusal;
        private String nearMiss;
        private List<HistoryTurn> history = List.of();
        private boolean adversarial;
        private List<String> injectionPhrases = List.of();

        Case(String id) {
            this.id = id;
        }

        Case category(String value) {
            this.category = value;
            return this;
        }

        Case question(String value) {
            this.question = value;
            return this;
        }

        Case expectedDocuments(String... values) {
            this.expectedDocuments = List.of(values);
            return this;
        }

        Case expectedPages(Integer... values) {
            this.expectedPages = List.of(values);
            return this;
        }

        Case expectedKeywords(String... values) {
            this.expectedKeywords = List.of(values);
            return this;
        }

        Case expectedClaims(String... values) {
            this.expectedClaims = List.of(values);
            return this;
        }

        Case expectRefusal(boolean value) {
            this.expectRefusal = value;
            return this;
        }

        Case nearMiss(String value) {
            this.nearMiss = value;
            return this;
        }

        Case history(HistoryTurn... values) {
            this.history = List.of(values);
            return this;
        }

        Case adversarial(boolean value) {
            this.adversarial = value;
            return this;
        }

        Case injectionPhrases(String... values) {
            this.injectionPhrases = List.of(values);
            return this;
        }

        EvalCase build() {
            return new EvalCase(id, category, question, expectedDocument, expectedDocuments,
                    expectedPages, expectedKeywords, expectedClaims, expectRefusal, nearMiss,
                    history, adversarial, injectionPhrases);
        }
    }
}
