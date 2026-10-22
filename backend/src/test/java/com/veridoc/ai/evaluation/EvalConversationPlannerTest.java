package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the evaluator conversation isolation logic.
 *
 * <p>These are the regression tests for the Stage 2 isolation requirement: an
 * independent case must not inherit another case's history, and a multi-turn
 * case must retain only the turns it declared itself.
 */
class EvalConversationPlannerTest {

    @Test
    @DisplayName("independent cases never share a conversation")
    void independentCasesNeverShareAConversation() {
        EvalCase first = answerable("first");
        EvalCase second = answerable("second");

        EvalConversationPlanner planner = new EvalConversationPlanner(List.of(first, second));

        assertThat(planner.planFor("first").conversationKey())
                .isNotEqualTo(planner.planFor("second").conversationKey());
        assertThat(planner.sharesConversation("first", "second")).isFalse();
        assertThat(planner.sharesConversation("second", "first")).isFalse();
    }

    @Test
    @DisplayName("an independent case expects an empty history")
    void independentCasesExpectNoHistory() {
        EvalConversationPlanner planner =
                new EvalConversationPlanner(List.of(answerable("only")));

        EvalConversationPlanner.Plan plan = planner.planFor("only");
        assertThat(plan.multiTurn()).isFalse();
        assertThat(plan.priorTurns()).isEmpty();
        assertThat(plan.expectedHistoryMessages()).isZero();
    }

    @Test
    @DisplayName("a multi-turn case replays exactly its own turns, two messages per turn")
    void multiTurnCaseReplaysOnlyItsOwnTurns() {
        EvalCase multiTurn = multiTurn("turn", "first question", "second question");

        EvalConversationPlanner planner = new EvalConversationPlanner(List.of(multiTurn));

        EvalConversationPlanner.Plan plan = planner.planFor("turn");
        assertThat(plan.multiTurn()).isTrue();
        assertThat(plan.priorTurns()).containsExactly(
                HistoryTurn.user("first question"), HistoryTurn.user("second question"));
        assertThat(plan.expectedHistoryMessages()).isEqualTo(4);
    }

    @Test
    @DisplayName("two multi-turn cases do not share turns or conversations")
    void multiTurnCasesKeepTheirOwnHistoryOnly() {
        EvalCase one = multiTurn("one", "one-previous");
        EvalCase two = multiTurn("two", "two-previous");

        EvalConversationPlanner planner = new EvalConversationPlanner(List.of(one, two));

        assertThat(planner.planFor("one").priorTurns())
                .containsExactly(HistoryTurn.user("one-previous"));
        assertThat(planner.planFor("two").priorTurns())
                .containsExactly(HistoryTurn.user("two-previous"));
        assertThat(planner.sharesConversation("one", "two")).isFalse();
        assertThat(planner.planFor("one").expectedHistoryMessages()).isEqualTo(2);
        assertThat(planner.planFor("two").expectedHistoryMessages()).isEqualTo(2);
    }

    @Test
    @DisplayName("history declared by a non multi-turn case is never replayed")
    void historyOutsideMultiTurnIsNeverReplayed() {
        EvalCase leaky = new EvalCase("leaky", "direct", "q", null, List.of("x.pdf"), List.of(),
                List.of("k"), List.of("cl"), false, null, List.of(HistoryTurn.user("h")), false,
                List.of());

        EvalConversationPlanner planner = new EvalConversationPlanner(List.of(leaky));

        assertThat(planner.planFor("leaky").priorTurns()).isEmpty();
        assertThat(planner.planFor("leaky").expectedHistoryMessages()).isZero();
    }

    @Test
    @DisplayName("an unknown case has no plan")
    void unknownCaseHasNoPlan() {
        EvalConversationPlanner planner =
                new EvalConversationPlanner(List.of(answerable("known")));

        assertThatThrownBy(() -> planner.planFor("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    private static EvalCase answerable(String id) {
        return new EvalCase(id, "direct", "q" + id, null, List.of("x.pdf"), List.of(),
                List.of("k"), List.of("cl"), false, null, List.of(), false, List.of());
    }

    private static EvalCase multiTurn(String id, String... priorQuestions) {
        List<HistoryTurn> history = java.util.Arrays.stream(priorQuestions)
                .map(HistoryTurn::user)
                .toList();
        return new EvalCase(id, "multi-turn", "q" + id, null, List.of("x.pdf"), List.of(),
                List.of("k"), List.of("cl"), false, null, history, false, List.of());
    }
}
