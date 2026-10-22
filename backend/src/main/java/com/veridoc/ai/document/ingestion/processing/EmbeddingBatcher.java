package com.veridoc.ai.document.ingestion.processing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.infrastructure.config.AiConfig.EmbeddingDimensions;
import com.veridoc.ai.infrastructure.config.EmbeddingOptionsFactory;

/**
 * Embeds document chunks in provider-safe batches.
 *
 * <p>The configured batch size is applied here rather than left to the model
 * client: a document can contain hundreds of chunks, and sending all of them in
 * one request risks provider request limits and oversized all-or-nothing
 * failures. Batches are sent sequentially through the same embedding model, so
 * the existing timeout/retry policy still applies to every provider call.
 *
 * <p>The returned list is positionally aligned with the input list. A batch
 * response is validated for cardinality and every vector is validated against
 * the configured embedding dimensions before any result is returned. If any
 * batch fails, no vectors are returned and the ingestion pipeline persists no
 * chunks.
 */
@Component
public class EmbeddingBatcher {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingDimensions embeddingDimensions;

    public EmbeddingBatcher(EmbeddingModel embeddingModel,
                            EmbeddingProperties embeddingProperties,
                            EmbeddingDimensions embeddingDimensions) {
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = embeddingProperties;
        this.embeddingDimensions = embeddingDimensions;
    }

    /**
     * Embeds every text in order, using at most {@code batch-size} inputs per
     * provider request.
     *
     * @return one vector per input, in the same order as {@code texts}
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("Texts must not be null");
        }
        if (texts.isEmpty()) {
            return List.of();
        }

        int batchSize = embeddingProperties.batchSize();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = List.copyOf(texts.subList(start, end));
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(
                    batch, EmbeddingOptionsFactory.forDocuments(embeddingProperties)));

            List<Embedding> results = response == null ? null : response.getResults();
            int received = results == null ? 0 : results.size();
            if (received != batch.size()) {
                throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED,
                        "Embedding count mismatch in batch " + (start / batchSize + 1)
                                + ": expected " + batch.size() + " but received " + received);
            }

            for (int i = 0; i < batch.size(); i++) {
                Embedding embedding = results.get(i);
                float[] vector = embedding == null ? null : embedding.getOutput();
                if (vector == null || vector.length == 0) {
                    throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED,
                            "Empty embedding returned for chunk " + (start + i));
                }
                embeddingDimensions.requireMatch(vector.length, "chunk embedding");
                vectors.add(vector);
            }
        }
        return List.copyOf(vectors);
    }
}
