package com.veridoc.ai.evaluation.live;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * A {@link ChatModel} that charges the shared budget before delegating.
 *
 * <p>It is a plain decorator with a public constructor and no Spring
 * annotation. Nothing scans it, nothing auto-configures it: the live context
 * installs it through a {@link org.springframework.beans.factory.config.BeanPostProcessor}
 * that only exists inside a context created by {@link LiveEvalGuardedContext}.
 * Ordinary deterministic tests keep their stub beans untouched.
 *
 * <p>Both entry points application code uses are charged:
 * {@link #call(Prompt)} and {@link #stream(Prompt)}. The convenience defaults
 * on {@link ChatModel} ({@code call(String)}, {@code call(Message...)},
 * {@code stream(String)}) are implemented in terms of those two, so they are
 * charged exactly once as well.
 *
 * <p>The charge happens <em>before</em> the delegate is touched, so a rejected
 * call never reaches the provider. Nothing here logs, stores or inspects the
 * prompt: a budget breach must not leak document content into a log.
 */
public final class BudgetedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LiveEvalBudget budget;

    /**
     * @param delegate the real model, never null
     * @param budget the shared run budget, never null
     */
    public BudgetedChatModel(ChatModel delegate, LiveEvalBudget budget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must be provided");
        this.budget = Objects.requireNonNull(budget, "budget must be provided");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        budget.recordChatCall();
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        budget.recordChatCall();
        return delegate.stream(prompt);
    }

    /**
     * Delegated so a consumer that resolves options sees the real model's
     * configuration. Marked deprecated for removal in Spring AI 2.0, but this
     * codebase never calls it; the override exists only so the decorator cannot
     * silently change behaviour if something does.
     */
    @SuppressWarnings("removal")
    @Override
    public ChatOptions getDefaultOptions() {
        // Metadata, not a provider call.
        return delegate.getDefaultOptions();
    }

    /** The wrapped model, for assertions and diagnostics. */
    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "BudgetedChatModel[" + budget.summary() + "]";
    }
}
