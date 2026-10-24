package com.veridoc.ai.evaluation.live;

import java.util.LinkedHashMap;
import java.util.Map;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalObservation;
import com.veridoc.ai.evaluation.EvalReport;
import com.veridoc.ai.evaluation.HistoryTurn;
import com.veridoc.ai.evaluation.RagEvaluator;

/**
 * Runs the selected cases through a transport and grades them with the same
 * {@link RagEvaluator} the deterministic suite uses.
 *
 * <p>The runner is responsible for the rules that are easy to get wrong in a
 * live run: one conversation per case, exactly the declared history, one shared
 * budget, and a wall-clock measurement around each case. It has no Spring
 * dependency and no knowledge of providers, which is what makes it unit
 * testable.
 */
public final class LiveEvalRunner {

    private final LiveEvalTransport transport;
    private final LiveEvalBudget budget;
    private final EvalConversationPlanner planner;

    public LiveEvalRunner(LiveEvalTransport transport, LiveEvalBudget budget,
                          EvalConversationPlanner planner) {
        this.transport = transport;
        this.budget = budget;
        this.planner = planner;
    }

    public Result run(EvalDataset dataset, int retrievalK) throws Exception {
        Map<String, LiveCaseRun> runs = new LinkedHashMap<>();

        EvalReport report = RagEvaluator.evaluate(dataset, evalCase -> {
            budget.recordCase();
            long started = System.nanoTime();
            EvalConversationPlanner.Plan plan = planner.planFor(evalCase.id());

            LiveEvalTransport.CaseSession session = transport.openCase(evalCase, plan);
            int expectedHistory = 0;
            for (HistoryTurn turn : plan.priorTurns()) {
                session.replay(turn, expectedHistory);
                expectedHistory += 2;
            }

            long finalStarted = System.nanoTime();
            LiveTurn live = session.askFinal(evalCase);
            long finalLatencyMs = elapsedMillis(finalStarted);
            long caseLatencyMs = elapsedMillis(started);

            if (live.historyMessages() != plan.expectedHistoryMessages()) {
                throw new IllegalStateException("case '" + evalCase.id() + "' saw "
                        + live.historyMessages() + " history messages, expected "
                        + plan.expectedHistoryMessages());
            }

            runs.put(evalCase.id(), new LiveCaseRun(evalCase.id(), caseLatencyMs,
                    finalLatencyMs, live.modelLatencyMs() == null ? 0L : live.modelLatencyMs(),
                    live.promptTokens(), live.completionTokens(), live.totalTokens(),
                    plan.expectedHistoryMessages()));

            return new EvalObservation(live.evidence(), live.citations(), live.refusal(),
                    live.answer());
        }, retrievalK);

        return new Result(report, Map.copyOf(runs));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    public record Result(EvalReport report, Map<String, LiveCaseRun> caseRuns) {
    }

    /**
     * Per-case measurements that belong to the run, not to the grading model.
     * Deliberately carries no question or answer text.
     */
    public record LiveCaseRun(
            String caseId,
            long caseLatencyMs,
            long finalTurnLatencyMs,
            long modelLatencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            int historyMessages
    ) {
    }
}
