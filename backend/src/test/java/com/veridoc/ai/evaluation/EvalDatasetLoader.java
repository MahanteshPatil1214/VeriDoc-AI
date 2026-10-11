package com.veridoc.ai.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import tools.jackson.databind.json.JsonMapper;

/** Loads a {@link EvalDataset} JSON document from the classpath. */
public final class EvalDatasetLoader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EvalDatasetLoader() {
    }

    public static EvalDataset load(String classpathResource) {
        try (InputStream in = EvalDatasetLoader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Missing RAG evaluation dataset: " + classpathResource);
            }
            return MAPPER.readValue(in, EvalDataset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read RAG evaluation dataset " + classpathResource, e);
        }
    }
}
