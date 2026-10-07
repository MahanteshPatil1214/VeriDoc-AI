package com.veridoc.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic {@link EmbeddingModel} for integration tests.
 *
 * <p>Gemini is never contacted. Vectors are unit-normalised and derived from
 * the input text, so the same chunk always yields the same vector (needed for
 * reproducible ordering) while different chunks yield different vectors.
 * Dimensions match {@code veridoc.embedding.dimensions} (768).
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
        Random random = new Random(text == null ? 0L : text.hashCode());
        double squaredNorm = 0.0;
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (random.nextDouble() * 2.0 - 1.0);
            squaredNorm += (double) v[i] * v[i];
        }
        double norm = Math.sqrt(squaredNorm);
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }
}