package com.veridoc.ai.evaluation.live;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.HistoryTurn;

/**
 * The service-level transport used by {@link LiveEvalRunner}.
 *
 * <p>The live implementation calls the production {@code ConversationService}
 * and {@code ChatService}; a fake implementation is used by offline tests. The
 * runner owns case budgeting, conversation isolation and history assertions, so
 * those rules are tested without a provider.
 */
public interface LiveEvalTransport {

    /** Opens the isolated conversation for one case. */
    CaseSession openCase(EvalCase evalCase, EvalConversationPlanner.Plan plan);

    interface CaseSession {

        /** Replays one declared prior user turn and checks the history it saw. */
        void replay(HistoryTurn turn, int expectedHistoryMessages);

        /** Asks the case's own question and captures the exact evidence bundle. */
        LiveTurn askFinal(EvalCase evalCase);
    }
}
