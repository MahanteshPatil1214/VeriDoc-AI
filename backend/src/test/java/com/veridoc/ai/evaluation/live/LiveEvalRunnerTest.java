package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvidencePassage;
import com.veridoc.ai.evaluation.HistoryTurn;

class LiveEvalRunnerTest {

    private static final UUID CHUNK = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("the runner isolates cases, replays declared history and grades with RagEvaluator")
    void runsCasesInIsolatedConversations() throws Exception {
        EvalDataset dataset = dataset();
        FakeTransport transport = new FakeTransport(false);
        LiveEvalBudget budget = new LiveEvalBudget(10, 40, 40, Duration.ofMinutes(5));

        LiveEvalRunner.Result result = new LiveEvalRunner(transport, budget,
                new EvalConversationPlanner(dataset.cases())).run(dataset, 8);

        assertThat(budget.cases()).isEqualTo(2);
        assertThat(transport.openedIds).containsExactly("multi-turn-case", "refusal-case");
        assertThat(transport.openedKeys).doesNotHaveDuplicates();
        assertThat(result.report().results()).hasSize(2);
        assertThat(result.caseRuns()).hasSize(2);
        assertThat(result.report().hitRate()).isEqualTo(1.0);
        assertThat(result.report().meanCitationPrecision()).isEqualTo(1.0);
        assertThat(result.report().refusalCorrectness()).isEqualTo(1.0);
        assertThat(result.caseRuns().get("multi-turn-case").historyMessages()).isEqualTo(2);
        assertThat(result.caseRuns().get("multi-turn-case").promptTokens()).isEqualTo(10);
        assertThat(result.caseRuns().get("refusal-case").totalTokens()).isZero();
    }

    @Test
    @DisplayName("a case whose final turn does not see its declared history fails")
    void rejectsLostHistory() {
        EvalDataset dataset = dataset();
        LiveEvalBudget budget = new LiveEvalBudget(10, 40, 40, Duration.ofMinutes(5));

        assertThatThrownBy(() -> new LiveEvalRunner(new FakeTransport(true), budget,
                new EvalConversationPlanner(dataset.cases())).run(dataset, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("history messages");
    }

    @Test
    @DisplayName("the case budget rejects the next case before its conversation is opened")
    void enforcesTheCaseBudgetBeforeOpeningAnything() {
        EvalDataset dataset = dataset();
        FakeTransport transport = new FakeTransport(false);
        LiveEvalBudget budget = new LiveEvalBudget(1, 40, 40, Duration.ofMinutes(5));

        assertThatThrownBy(() -> new LiveEvalRunner(transport, budget,
                new EvalConversationPlanner(dataset.cases())).run(dataset, 8))
                .isInstanceOf(LiveEvalBudgetExceededException.class);

        assertThat(budget.cases()).isEqualTo(2);
        assertThat(transport.openedIds).containsExactly("multi-turn-case");
    }

    private static EvalDataset dataset() {
        EvalCase multiTurn = new EvalCase(
                "multi-turn-case", "multi-turn", "final question?",
                "a.pdf", List.of(), List.of(1), List.of("answer"), List.of(),
                false, null, List.of(HistoryTurn.user("prior question?")), false, List.of());
        EvalCase refusal = new EvalCase(
                "refusal-case", "refusal", "unknown question?",
                null, List.of(), List.of(), List.of(), List.of(),
                true, "a.pdf", List.of(), false, List.of());
        return new EvalDataset("runner-test",
                List.of(new EvalDataset.CorpusDocument("a.pdf", "corpus text", null)),
                List.of(multiTurn, refusal));
    }

    private static final class FakeTransport implements LiveEvalTransport {

        private final boolean loseFinalHistory;
        private final List<String> openedIds = new ArrayList<>();
        private final List<String> openedKeys = new ArrayList<>();
        private final Map<String, FakeSession> sessions = new LinkedHashMap<>();

        private FakeTransport(boolean loseFinalHistory) {
            this.loseFinalHistory = loseFinalHistory;
        }

        @Override
        public CaseSession openCase(EvalCase evalCase, EvalConversationPlanner.Plan plan) {
            openedIds.add(evalCase.id());
            openedKeys.add(plan.conversationKey());
            FakeSession session = new FakeSession(loseFinalHistory);
            sessions.put(evalCase.id(), session);
            return session;
        }
    }

    private static final class FakeSession implements LiveEvalTransport.CaseSession {

        private final boolean loseFinalHistory;
        private int history;

        private FakeSession(boolean loseFinalHistory) {
            this.loseFinalHistory = loseFinalHistory;
        }

        @Override
        public void replay(HistoryTurn turn, int expectedHistoryMessages) {
            if (history != expectedHistoryMessages) {
                throw new IllegalStateException("fake session history mismatch");
            }
            history += 2;
        }

        @Override
        public LiveTurn askFinal(EvalCase evalCase) {
            EvidencePassage passage = new EvidencePassage("a.pdf", CHUNK, 1);
            boolean refusal = evalCase.expectRefusal();
            List<EvidencePassage> evidence = refusal ? List.of() : List.of(passage);
            List<EvidencePassage> citations = refusal ? List.of() : List.of(passage);
            return new LiveTurn(evidence, citations, refusal, refusal ? "no evidence" : "answer",
                    5L, 4L, refusal ? null : 10, refusal ? null : 5, refusal ? 0 : 15,
                    loseFinalHistory ? 0 : history);
        }
    }
}
