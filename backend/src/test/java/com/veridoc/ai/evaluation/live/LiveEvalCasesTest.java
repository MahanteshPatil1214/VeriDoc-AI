package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalDatasetLoader;

class LiveEvalCasesTest {

    private static final String DATASET = "/rag-eval/eval-dataset.json";

    private final EvalDataset full = EvalDatasetLoader.load(DATASET);

    @Test
    @DisplayName("the selection is deterministic, covers every category and keeps the full corpus")
    void selectsThePinnedTenCases() {
        EvalDataset first = LiveEvalCases.select(full);
        EvalDataset second = LiveEvalCases.select(full);

        assertThat(first.name()).isEqualTo(full.name() + "-live-10");
        assertThat(first.corpus()).hasSize(LiveEvalCases.EXPECTED_CORPUS_DOCUMENTS);
        assertThat(first.corpus()).containsExactlyElementsOf(full.corpus());
        assertThat(first.cases()).extracting(EvalCase::id)
                .containsExactlyElementsOf(LiveEvalCases.CASE_IDS);
        assertThat(second.cases()).extracting(EvalCase::id)
                .containsExactlyElementsOf(LiveEvalCases.CASE_IDS);
    }

    @Test
    @DisplayName("the ten cases cover all eight categories, refusal, history and page labels")
    void selectionIsRepresentative() {
        EvalDataset selected = LiveEvalCases.select(full);

        Set<String> categories = selected.cases().stream()
                .map(EvalCase::category).collect(Collectors.toSet());
        assertThat(categories).hasSize(8);
        assertThat(selected.cases()).anyMatch(EvalCase::expectRefusal);
        assertThat(selected.cases()).anyMatch(c -> !c.history().isEmpty());
        assertThat(selected.cases()).anyMatch(c -> !c.expectedPages().isEmpty());
        assertThat(selected.cases()).anyMatch(c -> !c.expectRefusal());
    }

    @Test
    @DisplayName("a budget that cannot hold ten cases is rejected before anything starts")
    void rejectsABudgetBelowTenCases() {
        assertThatThrownBy(() -> LiveEvalCases.select(full, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10");
    }

    @Test
    @DisplayName("a missing pinned case is a hard failure")
    void rejectsAMissingCase() {
        List<EvalCase> without = full.cases().stream()
                .filter(c -> !c.id().equals(LiveEvalCases.CASE_IDS.get(0)))
                .toList();
        EvalDataset broken = new EvalDataset(full.name(), full.corpus(), without);

        assertThatThrownBy(() -> LiveEvalCases.select(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing live evaluation case");
    }

    @Test
    @DisplayName("a corpus smaller than the shipped thirteen documents is rejected")
    void rejectsAnIncompleteCorpus() {
        EvalDataset broken = new EvalDataset(full.name(),
                full.corpus().subList(0, LiveEvalCases.EXPECTED_CORPUS_DOCUMENTS - 1),
                full.cases());

        assertThatThrownBy(() -> LiveEvalCases.select(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("13-document corpus");
    }
}
