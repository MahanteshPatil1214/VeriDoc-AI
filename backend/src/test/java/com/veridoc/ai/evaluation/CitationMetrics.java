package com.veridoc.ai.evaluation;

import java.util.List;

/** How faithfully the citations point at the expected source. */
public final class CitationMetrics {

    private CitationMetrics() {
    }

    /**
     * Fraction of citations that come from the expected document. 1.0 for an
     * empty citation list so a refusal is not penalised here.
     */
    public static double citationAccuracy(List<String> cited, String expected) {
        if (cited.isEmpty() || expected == null) {
            return 1.0;
        }
        long correct = cited.stream().filter(expected::equals).count();
        return (double) correct / cited.size();
    }
}
