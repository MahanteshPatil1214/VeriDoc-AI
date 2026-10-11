package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * What a single case run produced. {@code rankedDocuments} lists the cited
 * document filenames in retrieval order (best first), so ranking metrics can be
 * computed without touching persistence.
 */
public record EvalObservation(
        List<String> rankedDocuments,
        boolean refusal,
        String answer
) {
    public EvalObservation {
        rankedDocuments = rankedDocuments == null ? List.of() : List.copyOf(rankedDocuments);
        answer = answer == null ? "" : answer;
    }

    public static EvalObservation refused(String answer) {
        return new EvalObservation(List.of(), true, answer);
    }
}
