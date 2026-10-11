package com.veridoc.ai.evaluation;

import java.util.List;
import java.util.Locale;

/** Pure answer-text metrics (no Spring, no I/O). */
public final class AnswerMetrics {

    private AnswerMetrics() {
    }

    /** Fraction of expected keywords (case-insensitive) present in the answer. */
    public static double keywordCoverage(String answer, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 1.0;
        }
        String haystack = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        long found = keywords.stream()
                .filter(keyword -> haystack.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / keywords.size();
    }
}
