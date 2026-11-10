package com.veridoc.ai.config.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetrievalPropertiesTest {

    @Test
    @DisplayName("defaults match expectations and normalize weights")
    void defaultsAndNormalization() {
        RetrievalProperties props = new RetrievalProperties(
                8, 0.15, 40, 20, true, 20, 0.7, 0.3);

        assertThat(props.topK()).isEqualTo(8);
        assertThat(props.minScore()).isEqualTo(0.15);
        assertThat(props.candidateK()).isEqualTo(40);
        assertThat(props.keywordEnabled()).isTrue();
        assertThat(props.normalisedVectorWeight()).isEqualTo(0.7);
        assertThat(props.normalisedKeywordWeight()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("when keyword is disabled, vector weight is 1.0 and keyword weight is 0.0")
    void disabledKeywordNormalization() {
        RetrievalProperties props = new RetrievalProperties(
                8, 0.15, 40, 20, false, 20, 0.7, 0.3);

        assertThat(props.normalisedVectorWeight()).isEqualTo(1.0);
        assertThat(props.normalisedKeywordWeight()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("validates bounds and constraints")
    void validationBounds() {
        assertThatThrownBy(() -> new RetrievalProperties(0, 0.15, 40, 20, true, 20, 0.7, 0.3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k must be positive");

        assertThatThrownBy(() -> new RetrievalProperties(8, 0.15, 5, 20, true, 20, 0.7, 0.3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate-k must be >= top-k");

        assertThatThrownBy(() -> new RetrievalProperties(8, -1.5, 40, 20, true, 20, 0.7, 0.3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-score must be a cosine similarity");
    }
}

