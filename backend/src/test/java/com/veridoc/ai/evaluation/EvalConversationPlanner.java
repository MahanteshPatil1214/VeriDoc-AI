package com.veridoc.ai.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans which conversation each evaluation case runs in.
 *
 * <p>Stage 2 requires that one case can never inherit another case's history.
 * The rule is expressed here, once, in a pure object that can be unit-tested
 * without Spring, a database or an HTTP server:
 *
 * <ul>
 *   <li>every case owns a conversation keyed by its own case id, so no two
 *       cases — not even two multi-turn cases — ever share a conversation;</li>
 *   <li>only a {@code multi-turn} case replays history, and it replays exactly
 *       the turns it declared, never a turn contributed by another case;</li>
 *   <li>one replayed user turn adds two stored messages (the user question and
 *       the assistant reply), so a case with {@code n} declared turns must see
 *       {@code 2n} history messages in its final turn.</li>
 * </ul>
 *
 * <p>Production behaviour is unchanged: this class only decides which
 * conversation the harness talks to and what the expected history size is.
 */
public final class EvalConversationPlanner {

    public static final String MULTI_TURN = "multi-turn";

    /** A replayed user turn produces a user message and an assistant reply. */
    private static final int MESSAGES_PER_TURN = 2;

    /**
     * What the harness must do for one case.
     *
     * @param caseId the case this plan belongs to
     * @param conversationKey the conversation the case must run in; unique per case
     * @param multiTurn whether the case replays its own history
     * @param priorTurns the prior user turns the case replays, in order
     * @param expectedHistoryMessages how many history messages the final turn
     *        must see ({@code 0} for an independent case)
     */
    public record Plan(
            String caseId,
            String conversationKey,
            boolean multiTurn,
            List<HistoryTurn> priorTurns,
            int expectedHistoryMessages
    ) {
        public Plan {
            priorTurns = List.copyOf(priorTurns);
        }
    }

    private final Map<String, Plan> plans;

    public EvalConversationPlanner(List<EvalCase> cases) {
        Map<String, Plan> byCase = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            boolean multiTurn = MULTI_TURN.equals(evalCase.category());
            List<HistoryTurn> priorTurns = multiTurn ? evalCase.history() : List.of();
            byCase.put(evalCase.id(), new Plan(
                    evalCase.id(),
                    "eval:" + evalCase.id(),
                    multiTurn,
                    priorTurns,
                    priorTurns.size() * MESSAGES_PER_TURN));
        }
        this.plans = Collections.unmodifiableMap(byCase);
    }

    public Plan planFor(String caseId) {
        Plan plan = plans.get(caseId);
        if (plan == null) {
            throw new IllegalArgumentException("no plan for evaluation case '" + caseId + "'");
        }
        return plan;
    }

    public List<Plan> plans() {
        return List.copyOf(new ArrayList<>(plans.values()));
    }

    /**
     * Whether two cases would run in the same conversation.
     *
     * <p>Always {@code false} for distinct cases: this is the isolation rule the
     * regression test pins down.
     */
    public boolean sharesConversation(String firstCaseId, String secondCaseId) {
        return planFor(firstCaseId).conversationKey().equals(planFor(secondCaseId).conversationKey());
    }
}
