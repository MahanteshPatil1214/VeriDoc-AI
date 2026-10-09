package com.veridoc.ai.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, word-overlap {@link EmbeddingModel} for integration tests.
 *
 * <p>Gemini is never contacted. The vector is a unit-normalised bag-of-words
 * over the 768 configured dimensions, so two texts that share vocabulary end up
 * with high cosine similarity while unrelated texts stay near zero. This is
 * what lets tests assert the retrieval threshold behaves correctly (matched
 * query retrieves evidence, unrelated query yields "no evidence") without any
 * external service.
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 768;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> results = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            results.add(new Embedding(vector(instructions.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return v;
        }
        String[] tokens = text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int dim = Math.floorMod(token.hashCode(), v.length);
            v[dim] += 1.0f;
        }
        double norm = 0.0;
        for (float f : v) {
            norm += (double) f * f;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }
}