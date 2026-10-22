package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.veridoc.ai.config.properties.ChunkingProperties;
import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;

/** Pure dataset validation: blocks corrupt labels before the integration suite runs. */
class RagEvalDatasetTest {

    private static final String DATASET = "/rag-eval/eval-dataset.json";

    @Test
    void datasetIsValidAndHasTheRequiredShape() {
        EvalDataset dataset = EvalDatasetLoader.load(DATASET);
        EvalDatasetValidator.validate(dataset, 40);

        assertThat(dataset.cases()).hasSize(42);

        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String category : EvalDatasetValidator.CATEGORIES) {
            counts.put(category, dataset.cases().stream()
                    .filter(c -> category.equals(c.category())).count());
        }
        assertThat(counts.get("direct")).isEqualTo(8);
        assertThat(counts.get("refusal")).isEqualTo(6);
        assertThat(counts.get("multi-page")).isEqualTo(5);
        assertThat(counts.get("multi-document")).isEqualTo(5);
        assertThat(counts.get("exact-value")).isEqualTo(6);
        assertThat(counts.get("multi-turn")).isEqualTo(5);
        assertThat(counts.get("ambiguous")).isEqualTo(3);
        assertThat(counts.get("prompt-injection")).isEqualTo(4);

        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (EvalCase evalCase : dataset.cases()) {
            assertThat(evalCase.id()).isNotBlank();
            assertThat(evalCase.question()).isNotBlank();
            referenced.addAll(evalCase.resolvedDocuments());
        }
        for (CorpusDocument doc : dataset.corpus()) {
            assertThat(referenced).contains(doc.filename());
            assertThat(doc.pageTexts()).isNotEmpty();
            for (String page : doc.pageTexts()) {
                assertThat(EvalDatasetValidator.countTokens(page))
                        .as("%s page must meet the chunker minimum", doc.filename())
                        .isGreaterThanOrEqualTo(40);
            }
        }
    }

    @Test
    void validatorRejectsCorruptedEntries() {
        EvalDataset base = EvalDatasetLoader.load(DATASET);
        CorpusDocument corpus = base.corpus().get(0);
        EvalCase refusal = base.cases().stream()
                .filter(c -> "refusal-career-break".equals(c.id())).findFirst().orElseThrow();

        // duplicate case id
        assertViolation(eval(List.of(corpus), List.of(refusal, refusal)),
                "id", "duplicate case id");

        // unknown document
        EvalCase unknownDoc = new EvalCase("x", "direct", "q", null, List.of("missing.pdf"),
                List.of(), List.of("ok"), List.of("claim"), false, null, List.of(), false, List.of());
        // missing.pdf not in corpus
        EvalDataset ds2 = eval(List.of(corpus), List.of(unknownDoc));
        try {
            EvalDatasetValidator.validate(ds2, 40);
            org.junit.jupiter.api.Assertions.fail("expected validation to fail");
        } catch (EvalDatasetValidator.EvalDatasetValidationException ex) {
            assertThat(ex.violations()).anyMatch(v -> v.message().contains("missing.pdf")
                    || v.message().toLowerCase(java.util.Locale.ROOT).contains("unknown corpus"));
        }

        // refusal with expected document
        EvalCase badRefusal = new EvalCase("r2", "refusal", "q2", null, List.of(corpus.filename()),
                List.of(), List.of(), List.of(), true, "near miss", List.of(), false, List.of());
        assertViolation(eval(List.of(corpus), List.of(badRefusal)),
                "expectedDocuments", "refusal");

        // history role not replayable
        EvalCase badHistory = new EvalCase("m1", "multi-turn", "q3", null,
                List.of(corpus.filename()), List.of(), List.of("ok"), List.of("claim"),
                false, null, List.of(new HistoryTurn("assistant", "hi")), false, List.of());
        assertViolation(eval(List.of(corpus), List.of(badHistory)),
                "role", "not replayable");

        // non-multi-turn with history
        EvalCase leaked = new EvalCase("d1", "direct", "q4", null, List.of(corpus.filename()),
                List.of(), List.of("ok"), List.of("claim"), false, null,
                List.of(HistoryTurn.user("hi")), false, List.of());
        assertViolation(eval(List.of(corpus), List.of(leaked)), "history", "multi-turn");

        // adversarial mismatch
        EvalCase badAdv = new EvalCase("p1", "prompt-injection", "q5", null,
                List.of(corpus.filename()), List.of(), List.of("ok"), List.of("claim"),
                false, null, List.of(), true, List.of());
        assertViolation(eval(List.of(corpus), List.of(badAdv)), "injectionPhrases", "phrases");
    }

    private static void assertViolation(EvalDataset dataset, String fieldFragment,
                                        String messageFragment) {
        try {
            EvalDatasetValidator.validate(dataset, 40);
            org.junit.jupiter.api.Assertions.fail("expected validation failure");
        } catch (EvalDatasetValidator.EvalDatasetValidationException ex) {
            assertThat(ex.violations()).anyMatch(v -> v.field().contains(fieldFragment)
                    && v.message().toLowerCase(java.util.Locale.ROOT)
                    .contains(messageFragment.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static EvalDataset eval(List<CorpusDocument> corpus, List<EvalCase> cases) {
        return new EvalDataset("test", corpus, cases);
    }
}
