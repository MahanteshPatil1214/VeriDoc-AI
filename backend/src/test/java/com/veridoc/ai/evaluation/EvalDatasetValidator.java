package com.veridoc.ai.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;

/**
 * Deterministic structural and semantic validation for an {@link EvalDataset}.
 *
 * <p>The evaluator must fail before it runs a single case if the dataset is
 * corrupt: a missing label silently scores zero, a typo in an expected document
 * silently scores zero, and a refusal case without a near-miss document hides
 * the fact that the corpus was never actually challenging. Every violation names
 * the offending case (or corpus entry) and the field.
 *
 * <p>Validation is pure: no Spring, no I/O, no randomness. The chunker's minimum
 * page size is passed in by the caller so this class never duplicates a
 * production default.
 */
public final class EvalDatasetValidator {

    public static final Set<String> CATEGORIES = Set.of(
            "direct",
            "refusal",
            "multi-page",
            "multi-document",
            "exact-value",
            "multi-turn",
            "ambiguous",
            "prompt-injection");

    public static final String ADVERSARIAL_CATEGORY = "prompt-injection";

    private static final String DATASET = "<dataset>";
    private static final String CORPUS = "<corpus>";

    private EvalDatasetValidator() {
    }

    /** One problem, located by case id and field. */
    public record Violation(String caseId, String field, String message) {

        @Override
        public String toString() {
            return "case '" + caseId + "' field '" + field + "': " + message;
        }
    }

    /** Thrown when a dataset fails validation; carries every violation, not just the first. */
    public static final class EvalDatasetValidationException extends IllegalArgumentException {

        private final transient List<Violation> violations;

        EvalDatasetValidationException(List<Violation> violations) {
            super(format(violations));
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() {
            return violations;
        }

        private static String format(List<Violation> violations) {
            StringBuilder sb = new StringBuilder("Invalid RAG evaluation dataset:");
            for (Violation violation : violations) {
                sb.append(System.lineSeparator()).append("  - ").append(violation);
            }
            return sb.toString();
        }
    }

    /** @throws EvalDatasetValidationException if any invariant is broken. */
    public static void validate(EvalDataset dataset, int minTokensPerPage) {
        List<Violation> violations = violations(dataset, minTokensPerPage);
        if (!violations.isEmpty()) {
            throw new EvalDatasetValidationException(violations);
        }
    }

    public static List<Violation> violations(EvalDataset dataset, int minTokensPerPage) {
        List<Violation> out = new ArrayList<>();
        if (dataset == null) {
            out.add(new Violation(DATASET, "dataset", "dataset must not be null"));
            return out;
        }
        if (isBlank(dataset.name())) {
            out.add(new Violation(DATASET, "name", "dataset name must not be blank"));
        }
        if (dataset.corpus().isEmpty()) {
            out.add(new Violation(DATASET, "corpus", "corpus must not be empty"));
        }

        Map<String, CorpusDocument> corpus = validateCorpus(dataset, out, minTokensPerPage);
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();
        for (EvalCase evalCase : dataset.cases()) {
            validateCase(evalCase, corpus, ids, questions, out);
        }
        return List.copyOf(out);
    }

    private static Map<String, CorpusDocument> validateCorpus(EvalDataset dataset,
                                                              List<Violation> out,
                                                              int minTokensPerPage) {
        Map<String, CorpusDocument> corpus = new LinkedHashMap<>();
        Set<String> lowerNames = new HashSet<>();
        for (CorpusDocument document : dataset.corpus()) {
            if (document == null) {
                out.add(new Violation(CORPUS, "document", "corpus document must not be null"));
                continue;
            }
            String id = isBlank(document.filename()) ? "<unnamed>" : document.filename();
            if (isBlank(document.filename())) {
                out.add(new Violation(CORPUS, "filename",
                        "corpus document filename must not be blank"));
            } else {
                if (!lowerNames.add(document.filename().toLowerCase(Locale.ROOT))) {
                    out.add(new Violation(id, "filename",
                            "duplicate corpus document filename '" + document.filename() + "'"));
                }
                corpus.putIfAbsent(document.filename(), document);
            }

            boolean hasText = !isBlank(document.text());
            boolean hasPages = !document.pages().isEmpty();
            if (hasText && hasPages) {
                out.add(new Violation(id, "pages",
                        "set either 'text' (single page) or 'pages' (multi page), not both"));
            }
            if (!hasText && !hasPages) {
                out.add(new Violation(id, "pages", "corpus document has no text"));
                continue;
            }

            if (hasPages) {
                for (int i = 0; i < document.pages().size(); i++) {
                    String page = document.pages().get(i);
                    String field = "pages[" + i + "]";
                    if (isBlank(page)) {
                        out.add(new Violation(id, field, "page text must not be blank"));
                    } else if (minTokensPerPage > 0
                            && countTokens(page) < minTokensPerPage) {
                        out.add(new Violation(id, field, "page has " + countTokens(page)
                                + " tokens, below the chunker minimum of " + minTokensPerPage
                                + " (the page would be dropped during ingestion)"));
                    }
                }
            } else if (minTokensPerPage > 0 && countTokens(document.text()) < minTokensPerPage) {
                out.add(new Violation(id, "text", "document has " + countTokens(document.text())
                        + " tokens, below the chunker minimum of " + minTokensPerPage));
            }
        }
        return corpus;
    }

    private static void validateCase(EvalCase evalCase,
                                     Map<String, CorpusDocument> corpus,
                                     Set<String> ids,
                                     Set<String> questions,
                                     List<Violation> out) {
        if (evalCase == null) {
            out.add(new Violation("<null>", "case", "case must not be null"));
            return;
        }
        String id = isBlank(evalCase.id()) ? "<blank>" : evalCase.id();
        if (isBlank(evalCase.id())) {
            out.add(new Violation(id, "id", "case id must not be blank"));
        } else if (!ids.add(evalCase.id())) {
            out.add(new Violation(id, "id", "duplicate case id"));
        }

        if (isBlank(evalCase.question())) {
            out.add(new Violation(id, "question", "question must not be blank"));
        } else {
            String normalised = evalCase.question().trim().toLowerCase(Locale.ROOT);
            if (!questions.add(normalised)) {
                out.add(new Violation(id, "question",
                        "duplicate question text already used by another case"));
            }
        }

        validateCategory(evalCase, id, out);
        List<String> documents = validateDocuments(evalCase, corpus, id, out);
        validatePages(evalCase, documents, corpus, id, out);
        validateClaimsAndKeywords(evalCase, documents, corpus, id, out);
        validateRefusal(evalCase, documents, id, out);
        validateHistory(evalCase, id, out);
        validateAdversarial(evalCase, id, out);
        validateCategoryShape(evalCase, documents, corpus, id, out);
    }

    private static void validateCategory(EvalCase evalCase, String id, List<Violation> out) {
        if (isBlank(evalCase.category())) {
            out.add(new Violation(id, "category", "category is required"));
            return;
        }
        if (!CATEGORIES.contains(evalCase.category())) {
            out.add(new Violation(id, "category", "unknown category '" + evalCase.category()
                    + "'; expected one of " + CATEGORIES));
        }
    }

    private static List<String> validateDocuments(EvalCase evalCase,
                                                  Map<String, CorpusDocument> corpus,
                                                  String id,
                                                  List<Violation> out) {
        List<String> documents = new ArrayList<>();
        if (!isBlank(evalCase.expectedDocument())) {
            documents.add(evalCase.expectedDocument());
        }
        for (String document : evalCase.expectedDocuments()) {
            if (isBlank(document)) {
                out.add(new Violation(id, "expectedDocuments",
                        "expected document filename must not be blank"));
            } else {
                documents.add(document);
            }
        }
        if (!evalCase.expectedDocuments().isEmpty()
                && !isBlank(evalCase.expectedDocument())
                && !evalCase.expectedDocuments().contains(evalCase.expectedDocument())) {
            out.add(new Violation(id, "expectedDocument",
                    "legacy expectedDocument '" + evalCase.expectedDocument()
                            + "' is not present in expectedDocuments"));
        }
        for (String document : documents) {
            if (corpus != null && !corpus.containsKey(document)) {
                out.add(new Violation(id, "expectedDocuments",
                        "references unknown corpus document '" + document + "'"));
            }
        }
        return documents;
    }

    private static void validatePages(EvalCase evalCase,
                                      List<String> documents,
                                      Map<String, CorpusDocument> corpus,
                                      String id,
                                      List<Violation> out) {
        if (evalCase.expectedPages().isEmpty()) {
            return;
        }
        if (documents.size() != 1) {
            out.add(new Violation(id, "expectedPages",
                    "page labels require exactly one expected document, found "
                            + documents.size()));
            return;
        }
        CorpusDocument document = corpus.get(documents.get(0));
        int pageCount = document == null ? 0 : document.pageTexts().size();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < evalCase.expectedPages().size(); i++) {
            Integer page = evalCase.expectedPages().get(i);
            String field = "expectedPages[" + i + "]";
            if (page == null) {
                out.add(new Violation(id, field, "page must not be null"));
            } else if (page < 1) {
                out.add(new Violation(id, field, "page must be >= 1, found " + page));
            } else if (pageCount > 0 && page > pageCount) {
                out.add(new Violation(id, field, "page " + page
                        + " exceeds the page count (" + pageCount + ") of '" + documents.get(0) + "'"));
            } else if (!seen.add(page)) {
                out.add(new Violation(id, field, "duplicate page label " + page));
            }
        }
    }

    private static void validateClaimsAndKeywords(EvalCase evalCase,
                                                  List<String> documents,
                                                  Map<String, CorpusDocument> corpus,
                                                  String id,
                                                  List<Violation> out) {
        validateEntries(out, id, "expectedKeywords", evalCase.expectedKeywords());
        validateEntries(out, id, "expectedClaims", evalCase.expectedClaims());

        if (evalCase.isGrounded()) {
            if (evalCase.expectedKeywords().isEmpty()) {
                out.add(new Violation(id, "expectedKeywords",
                        "answerable case must declare at least one expected keyword"));
            }
            if (evalCase.expectedClaims().isEmpty()) {
                out.add(new Violation(id, "expectedClaims",
                        "answerable case must declare at least one expected claim"));
            }
            for (String keyword : evalCase.expectedKeywords()) {
                if (!isBlank(keyword) && !occursInAny(keyword, documents, corpus)) {
                    out.add(new Violation(id, "expectedKeywords",
                            "keyword '" + keyword + "' does not occur in any expected document"));
                }
            }
        }
    }

    private static void validateRefusal(EvalCase evalCase,
                                        List<String> documents,
                                        String id,
                                        List<Violation> out) {
        if (evalCase.expectRefusal()) {
            if (!documents.isEmpty()) {
                out.add(new Violation(id, "expectedDocuments",
                        "refusal case must not declare expected documents"));
            }
            if (!evalCase.expectedKeywords().isEmpty()) {
                out.add(new Violation(id, "expectedKeywords",
                        "refusal case must not declare expected keywords"));
            }
            if (!evalCase.expectedClaims().isEmpty()) {
                out.add(new Violation(id, "expectedClaims",
                        "refusal case must not declare expected claims"));
            }
            if (!evalCase.expectedPages().isEmpty()) {
                out.add(new Violation(id, "expectedPages",
                        "refusal case must not declare expected pages"));
            }
            if (isBlank(evalCase.nearMiss())) {
                out.add(new Violation(id, "nearMiss",
                        "refusal case must explain the plausible near-miss evidence it rejects"));
            }
        } else {
            if (documents.isEmpty()) {
                out.add(new Violation(id, "expectedDocuments",
                        "answerable case must declare at least one expected document"));
            }
            if (!isBlank(evalCase.nearMiss())) {
                out.add(new Violation(id, "nearMiss",
                        "answerable case must not declare a near-miss explanation"));
            }
        }
    }

    private static void validateHistory(EvalCase evalCase, String id, List<Violation> out) {
        boolean multiTurn = "multi-turn".equals(evalCase.category());
        if (!multiTurn && !evalCase.history().isEmpty()) {
            out.add(new Violation(id, "history",
                    "only multi-turn cases may declare conversation history"));
        }
        if (multiTurn && evalCase.history().isEmpty()) {
            out.add(new Violation(id, "history",
                    "multi-turn case must declare at least one prior user turn"));
        }
        for (int i = 0; i < evalCase.history().size(); i++) {
            HistoryTurn turn = evalCase.history().get(i);
            String field = "history[" + i + "]";
            if (turn == null) {
                out.add(new Violation(id, field, "history turn must not be null"));
                continue;
            }
            if (isBlank(turn.role())) {
                out.add(new Violation(id, field + ".role", "history role must not be blank"));
            } else if (!HistoryTurn.USER.equals(turn.role())) {
                out.add(new Violation(id, field + ".role", "history role '" + turn.role()
                        + "' is not replayable; declare prior user turns only"));
            }
            if (isBlank(turn.content())) {
                out.add(new Violation(id, field + ".content",
                        "history content must not be blank"));
            }
        }
    }

    private static void validateAdversarial(EvalCase evalCase, String id, List<Violation> out) {
        boolean injection = ADVERSARIAL_CATEGORY.equals(evalCase.category());
        if (evalCase.adversarial() && !injection) {
            out.add(new Violation(id, "adversarial",
                    "adversarial case must use category '" + ADVERSARIAL_CATEGORY + "'"));
        }
        if (!evalCase.adversarial() && injection) {
            out.add(new Violation(id, "adversarial",
                    "prompt-injection case must set adversarial=true"));
        }
        validateEntries(out, id, "injectionPhrases", evalCase.injectionPhrases());
        if (injection && evalCase.injectionPhrases().isEmpty()) {
            out.add(new Violation(id, "injectionPhrases",
                    "prompt-injection case must list the phrases the answer must not contain"));
        }
        if (!injection && !evalCase.injectionPhrases().isEmpty()) {
            out.add(new Violation(id, "injectionPhrases",
                    "only prompt-injection cases may declare injection phrases"));
        }
    }

    private static void validateCategoryShape(EvalCase evalCase,
                                              List<String> documents,
                                              Map<String, CorpusDocument> corpus,
                                              String id,
                                              List<Violation> out) {
        String category = evalCase.category();
        if ("refusal".equals(category) && !evalCase.expectRefusal()) {
            out.add(new Violation(id, "expectRefusal",
                    "refusal category requires expectRefusal=true"));
        }
        if ("multi-document".equals(category)) {
            if (documents.size() < 2) {
                out.add(new Violation(id, "expectedDocuments",
                        "multi-document case must declare at least two documents"));
            }
            if (evalCase.expectedClaims().size() < 2) {
                out.add(new Violation(id, "expectedClaims",
                        "multi-document case must declare at least two claims (one per source)"));
            }
        }
        if ("multi-page".equals(category)) {
            if (documents.size() != 1) {
                out.add(new Violation(id, "expectedDocuments",
                        "multi-page case must declare exactly one document"));
            }
            if (evalCase.expectedPages().isEmpty()) {
                out.add(new Violation(id, "expectedPages",
                        "multi-page case must declare the expected page"));
            }
            CorpusDocument document = documents.size() == 1 ? corpus.get(documents.get(0)) : null;
            if (document != null && document.pageTexts().size() < 2) {
                out.add(new Violation(id, "expectedDocuments",
                        "multi-page case must reference a document with at least two pages"));
            }
        }
        if ("exact-value".equals(category) && evalCase.expectedClaims().stream()
                .noneMatch(claim -> claim != null && claim.matches(".*\\d.*"))) {
            out.add(new Violation(id, "expectedClaims",
                    "exact-value case must include a claim containing a literal value"));
        }
    }

    private static void validateEntries(List<Violation> out, String id, String field,
                                        List<String> entries) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            String location = field + "[" + i + "]";
            if (isBlank(entry)) {
                out.add(new Violation(id, location, "entry must not be blank"));
            } else if (!seen.add(entry.trim().toLowerCase(Locale.ROOT))) {
                out.add(new Violation(id, location, "duplicate entry '" + entry + "'"));
            }
        }
    }

    private static boolean occursInAny(String keyword, List<String> documents,
                                       Map<String, CorpusDocument> corpus) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        for (String filename : documents) {
            CorpusDocument document = corpus.get(filename);
            if (document == null) {
                continue;
            }
            for (String page : document.pageTexts()) {
                if (page != null && page.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    static int countTokens(String text) {
        return text == null || text.isBlank() ? 0 : new StringTokenizer(text).countTokens();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
