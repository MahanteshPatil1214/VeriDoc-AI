package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure answer-text metrics, kept separate from retrieval and citation scoring. */
class AnswerMetricsTest {

    @Test
    @DisplayName("keyword coverage is case-insensitive and fractional")
    void keywordCoverage() {
        assertThat(AnswerMetrics.keywordCoverage(
                "The Vacation policy and ACCRUAL rules", List.of("vacation", "accrual")))
                .isEqualTo(1.0);
        assertThat(AnswerMetrics.keywordCoverage(
                "The Vacation policy", List.of("vacation", "accrual")))
                .isEqualTo(0.5);
        assertThat(AnswerMetrics.keywordCoverage("anything", List.of())).isEqualTo(1.0);
        assertThat(AnswerMetrics.keywordCoverage(null, List.of())).isEqualTo(1.0);
        assertThat(AnswerMetrics.keywordCoverage("", List.of("vacation"))).isZero();
    }
}
