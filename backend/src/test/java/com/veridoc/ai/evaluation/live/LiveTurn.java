package com.veridoc.ai.evaluation.live;

import java.util.List;

import com.veridoc.ai.evaluation.EvidencePassage;

/**
 * One measured chat turn.
 *
 * <p>{@code answer} is needed by the pure grading step (keyword coverage) and is
 * never written to a report. {@code evidence} is the exact list the prompt
 * builder handed to the model; {@code citations} is what the model actually
 * cited after the production citation resolver ran.
 */
public record LiveTurn(
        List<EvidencePassage> evidence,
        List<EvidencePassage> citations,
        boolean refusal,
        String answer,
        long latencyMs,
        Long modelLatencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        int historyMessages
) {
    public LiveTurn {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
