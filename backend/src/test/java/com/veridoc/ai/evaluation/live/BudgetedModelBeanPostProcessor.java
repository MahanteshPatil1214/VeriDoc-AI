package com.veridoc.ai.evaluation.live;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Replaces the real {@link ChatModel} and {@link EmbeddingModel} beans with
 * budgeted decorators.
 *
 * <p>Registered as a bean post-processor, not as an {@code @Primary} bean, so
 * the substitution happens at creation time: every consumer that is injected
 * with a model - the chat service, the query rewriter, the embedding batcher,
 * the retrieval service - receives the decorator, without changing a single
 * production class.
 *
 * <p>The post-processor is only ever added to the bean factory of a context
 * built by {@link LiveEvalGuardedContext}, and it refuses to wrap an already
 * wrapped model, so a decorator can never be stacked twice.
 */
final class BudgetedModelBeanPostProcessor implements BeanPostProcessor {

    private final LiveEvalBudget budget;

    BudgetedModelBeanPostProcessor(LiveEvalBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget must be provided");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BudgetedChatModel || bean instanceof BudgetedEmbeddingModel) {
            return bean;
        }
        if (bean instanceof ChatModel chatModel) {
            return new BudgetedChatModel(chatModel, budget);
        }
        if (bean instanceof EmbeddingModel embeddingModel) {
            return new BudgetedEmbeddingModel(embeddingModel, budget);
        }
        return bean;
    }
}
