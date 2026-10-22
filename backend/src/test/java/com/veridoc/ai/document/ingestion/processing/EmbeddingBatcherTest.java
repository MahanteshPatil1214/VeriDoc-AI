package com.veridoc.ai.document.ingestion.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.infrastructure.config.AiConfig.EmbeddingDimensions;

class EmbeddingBatcherTest {

    private static final int DIMENSIONS = 768;
    private static final String MODEL = "test-embedding-model";

    @Test
    @DisplayName("input smaller than the batch size is sent as one request")
    void fewerChunksThanBatchSize() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        EmbeddingBatcher batcher = batcher(model, 4);

        List<float[]> vectors = batcher.embedAll(texts(0, 1));

        assertThat(model.batchSizes()).containsExactly(2);
        assertThat(vectors).hasSize(2);
    }

    @Test
    @DisplayName("an input exactly equal to the batch size is sent as one full request")
    void exactlyOneFullBatch() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        EmbeddingBatcher batcher = batcher(model, 3);

        List<float[]> vectors = batcher.embedAll(texts(0, 1, 2));

        assertThat(model.batchSizes()).containsExactly(3);
        assertThat(vectors).hasSize(3);
    }

    @Test
    @DisplayName("multiple batches include a final partial batch without exceeding the configured size")
    void multipleBatchesWithPartialTail() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        EmbeddingBatcher batcher = batcher(model, 2);

        List<float[]> vectors = batcher.embedAll(texts(0, 1, 2, 3, 4));

        assertThat(model.batchSizes()).containsExactly(2, 2, 1);
        assertThat(vectors).hasSize(5);
    }

    @Test
    @DisplayName("vectors stay aligned with the original chunk order across batch boundaries")
    void orderingIsPreservedAcrossBatches() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        EmbeddingBatcher batcher = batcher(model, 2);

        List<float[]> vectors = batcher.embedAll(texts(0, 1, 2, 3, 4));

        assertThat(vectors)
                .extracting(vector -> vector[0])
                .containsExactly(0f, 1f, 2f, 3f, 4f);
    }

    @Test
    @DisplayName("a provider response with the wrong number of vectors fails the batch")
    void incorrectEmbeddingCountFails() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.vectorCountDelta = -1;
        EmbeddingBatcher batcher = batcher(model, 3);

        assertThatThrownBy(() -> batcher.embedAll(texts(0, 1, 2)))
                .isInstanceOf(AppException.class)
                .satisfies(error -> assertThat(((AppException) error).code())
                        .isEqualTo(ErrorCode.DOCUMENT_PROCESSING_FAILED))
                .hasMessageContaining("Embedding count mismatch");
    }

    @Test
    @DisplayName("a vector with the wrong dimensions is rejected")
    void incorrectDimensionsFail() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.vectorDimensions = DIMENSIONS - 1;
        EmbeddingBatcher batcher = batcher(model, 2);

        assertThatThrownBy(() -> batcher.embedAll(texts(0, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected " + DIMENSIONS + " dimensions")
                .hasMessageContaining("received " + (DIMENSIONS - 1));
    }

    @Test
    @DisplayName("empty input returns no vectors without calling the provider")
    void emptyInputSkipsProvider() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        EmbeddingBatcher batcher = batcher(model, 4);

        List<float[]> vectors = batcher.embedAll(List.of());

        assertThat(vectors).isEmpty();
        assertThat(model.batchSizes()).isEmpty();
    }

    @Test
    @DisplayName("a later batch failure is propagated after an earlier batch succeeds")
    void laterBatchFailurePropagates() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        model.failOnCall = 2;
        EmbeddingBatcher batcher = batcher(model, 2);

        assertThatThrownBy(() -> batcher.embedAll(texts(0, 1, 2, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated provider failure");
        assertThat(model.batchSizes()).containsExactly(2, 2);
    }

    private static EmbeddingBatcher batcher(EmbeddingModel model, int batchSize) {
        return new EmbeddingBatcher(
                model,
                new EmbeddingProperties(MODEL, DIMENSIONS, 1, batchSize,
                        "RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY"),
                new EmbeddingDimensions(MODEL, DIMENSIONS, 1));
    }

    private static List<String> texts(int... markers) {
        List<String> texts = new ArrayList<>(markers.length);
        for (int marker : markers) {
            texts.add("chunk-" + marker);
        }
        return texts;
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<Integer> batchSizes = new ArrayList<>();
        private int failOnCall = -1;
        private int vectorCountDelta;
        private int vectorDimensions = DIMENSIONS;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            batchSizes.add(instructions.size());
            if (batchSizes.size() == failOnCall) {
                throw new IllegalStateException("simulated provider failure");
            }

            int count = Math.max(0, instructions.size() + vectorCountDelta);
            List<Embedding> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                results.add(new Embedding(markerVector(instructions.get(i)), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return markerVector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }

        private float[] markerVector(String text) {
            float[] vector = new float[vectorDimensions];
            vector[0] = Float.parseFloat(text.substring("chunk-".length()));
            return vector;
        }
    }
}
