package com.veridoc.ai.evaluation.live;

import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;

/**
 * An {@link EmbeddingModel} that charges the shared budget before delegating.
 *
 * <p>The two methods that reach a provider are charged: {@link #call(EmbeddingRequest)}
 * and {@link #embed(Document)}. The default methods a caller is likely to use
 * - {@code embed(String)}, {@code embed(List<String>)},
 * {@code embedForResponse(List<String>)}, and the batching
 * {@code embed(List<Document>, ...)} - are all implemented by
 * {@link EmbeddingModel} in terms of {@link #call(EmbeddingRequest)}, so each
 * is charged exactly once and never twice.
 *
 * <p>{@link #dimensions()} is <strong>not</strong> charged: it is metadata. Spring AI's
 * Gemini embedding model hardcodes gemini-embedding-001 to 3072 in its static lookup table
 * and ignores runtime options. When the delegate is configured with an explicit output dimension
 * (e.g. 768), this decorator reports that configured width.
 *
 * <p>Like {@link BudgetedChatModel} it is a plain decorator: no Spring
 * annotation, public constructor, installed only by the live context factory.
 * No document text is logged or stored.
 */
public final class BudgetedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final LiveEvalBudget budget;

    /**
     * @param delegate the real model, never null
     * @param budget the shared run budget, never null
     */
    public BudgetedEmbeddingModel(EmbeddingModel delegate, LiveEvalBudget budget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must be provided");
        this.budget = Objects.requireNonNull(budget, "budget must be provided");
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        budget.recordEmbeddingCall();
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        budget.recordEmbeddingCall();
        return delegate.embed(document);
    }

    @Override
    public int dimensions() {
        // Metadata, not a provider call on the production model.
        if (delegate instanceof GoogleGenAiTextEmbeddingModel geminiModel
                && geminiModel.options != null
                && geminiModel.options.getDimensions() != null) {
            return geminiModel.options.getDimensions();
        }
        return delegate.dimensions();
    }

    /** The wrapped model, for assertions and diagnostics. */
    public EmbeddingModel delegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "BudgetedEmbeddingModel[" + budget.summary() + "]";
    }
}
