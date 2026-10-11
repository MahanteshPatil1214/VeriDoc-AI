package com.veridoc.ai.evaluation;

import java.util.List;

/** Pure document-level retrieval metrics (no Spring, no I/O). */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** True when the expected document is anywhere in the ranked list. */
    public static boolean hit(List<String> ranked, String expected) {
        return expected != null && ranked.contains(expected);
    }

    /** 1/rank of the first occurrence of {@code expected}, or 0 when absent. */
    public static double reciprocalRank(List<String> ranked, String expected) {
        if (expected == null) {
            return 0.0;
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (expected.equals(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Distinct documents in the top {@code k} over {@code k}. */
    public static double precisionAtK(List<String> ranked, int k) {
        if (k <= 0 || ranked.isEmpty()) {
            return 0.0;
        }
        long distinct = ranked.stream().limit(Math.min(k, ranked.size())).distinct().count();
        return (double) distinct / k;
    }

    /** 1.0 when the expected document is in the top {@code k}, else 0.0. */
    public static double recallAtK(List<String> ranked, String expected, int k) {
        if (expected == null) {
            return 0.0;
        }
        return ranked.stream().limit(Math.min(k, ranked.size()))
                .anyMatch(expected::equals) ? 1.0 : 0.0;
    }
}
