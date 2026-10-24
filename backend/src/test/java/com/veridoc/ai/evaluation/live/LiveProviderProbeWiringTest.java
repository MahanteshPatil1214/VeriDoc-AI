package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.ConfigurableApplicationContext;

import com.veridoc.ai.infrastructure.config.AiConfig;

/**
 * Proves that the probe slice really builds the production provider clients,
 * and that the budgeted decorators are installed around them.
 *
 * <p>It runs in the ordinary build, so it must not need a credential and must
 * not contact a provider. Both are satisfied by construction:
 *
 * <ul>
 *   <li>the keys are synthetic values generated here, passed as command-line
 *       properties, and never printed;</li>
 *   <li>no model method is invoked - the test only reads bean instances. Both
 *       SDKs build their HTTP clients lazily, so constructing the context opens
 *       no connection;</li>
 *   <li>the budget counters are asserted to be zero after startup, so nothing
 *       reached a provider through the decorator. Since the decorator is the
 *       only path the application uses, a stray call during startup would be
 *       charged and would fail this test.</li>
 * </ul>
 *
 * <p>The point of the test is the wiring the manual probe depends on: the real
 * {@code OpenAiChatModel} and {@code GoogleGenAiTextEmbeddingModel} are the
 * beans, and both are wrapped. If a Spring AI upgrade renames an
 * auto-configuration, this fails here rather than during an approved live run.
 */
class LiveProviderProbeWiringTest {

    /** Not a credential: never sent anywhere, never printed. */
    private static final String SYNTHETIC_KEY = "veridoc-wiring-check-value-0001";

    @Test
    @DisplayName("the slice builds real provider models wrapped in budgeted decorators")
    void sliceBuildsBudgetedRealModels() {
        Map<String, Object> systemProperties = Map.of(
                LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE,
                LiveEvalActivation.OPT_IN_PROPERTY, "true");
        Map<String, Object> environment = Map.of(
                LiveEvalCredentials.GEMINI_VARIABLE, SYNTHETIC_KEY,
                LiveEvalCredentials.GROQ_VARIABLE, SYNTHETIC_KEY);

        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady(systemProperties, environment);

        try (ConfigurableApplicationContext context = LiveEvalGuardedContext.create(
                plan, LiveProviderProbeApplication.class,
                "--spring.ai.openai.api-key=" + SYNTHETIC_KEY,
                "--spring.ai.google.genai.embedding.api-key=" + SYNTHETIC_KEY)) {

            ChatModel chatModel = context.getBean(ChatModel.class);
            EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);

            assertThat(chatModel)
                    .as("the live context must decorate every model bean")
                    .isInstanceOf(BudgetedChatModel.class);
            assertThat(embeddingModel).isInstanceOf(BudgetedEmbeddingModel.class);

            assertThat(((BudgetedChatModel) chatModel).delegate())
                    .as("the decorator must wrap the real Groq chat client, not a stub")
                    .isInstanceOf(OpenAiChatModel.class);
            assertThat(((BudgetedEmbeddingModel) embeddingModel).delegate())
                    .as("the decorator must wrap the real Gemini embedding client, not a stub")
                    .isInstanceOf(GoogleGenAiTextEmbeddingModel.class);

            assertThat(context.getBean(AiConfig.EmbeddingDimensions.class).dimensions())
                    .as("the configured embedding width drives the probe's dimension check")
                    .isPositive();

            assertThat(plan.budget().totalCalls())
                    .as("starting the context must not invoke a provider")
                    .isZero();
        }
    }
}
