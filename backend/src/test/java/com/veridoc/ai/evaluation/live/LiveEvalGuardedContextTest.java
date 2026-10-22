package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Flux;

/**
 * Proves the order of a live start: preflight first, then - and only then -
 * the application context, its provider-style beans and their budgeted
 * decorators.
 *
 * <p>The application used here is a throwaway {@link Configuration} whose
 * "provider" beans only count how often they are constructed and called. No
 * Spring AI auto-configuration is enabled, so no client is created, no
 * credential is read and nothing can reach the network.
 */
class LiveEvalGuardedContextTest {

    private static final String SYNTHETIC_GEMINI = "synthetic-gemini-credential-0001";
    private static final String SYNTHETIC_GROQ = "synthetic-groq-credential-0002";

    @BeforeEach
    void resetFakeApplication() {
        FakeApplication.reset();
    }

    @Test
    @DisplayName("a rejected preflight never reaches the application: no bean is constructed")
    void invalidConfigurationNeverStartsTheApplication() {
        assertThatThrownBy(() -> {
            // Nothing is activated, nothing is credentialed and the budget is invalid.
            Map<String, String> properties = new HashMap<>();
            properties.put(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY, "0");

            LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady(properties, Map.of());
            LiveEvalGuardedContext.create(plan, FakeApplication.class);
        }).isInstanceOf(LiveEvalPreflightException.class);

        assertThat(FakeApplication.PROVIDER_INITIALIZATIONS)
                .as("Spring must never have been asked to start")
                .hasValue(0);
        assertThat(FakeApplication.INJECTED_CHAT_MODEL).isNull();
    }

    @Test
    @DisplayName("a plan cannot be forged from an unvalidated decision or rejected credentials")
    void aPlanCannotBeForgedWithoutThePreflight() {
        LiveEvalActivation.Decision disabled = LiveEvalActivation.evaluate(Map.of(), Map.of());
        LiveEvalActivation.Decision enabled = LiveEvalActivation.evaluate(
                activatedProperties(), validCredentials());
        LiveEvalCredentials.Report rejected = LiveEvalCredentials.inspect(Map.of());
        LiveEvalCredentials.Report accepted = LiveEvalCredentials.inspect(validCredentials());
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 10, Duration.ofMinutes(1));

        assertThatThrownBy(() -> new LiveEvalPreflight.Plan(disabled, accepted, budget))
                .as("a plan requires an enabled activation decision")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveEvalPreflight.Plan(enabled, rejected, budget))
                .as("a plan requires acceptable credentials")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a spent wall-clock budget is rejected before the context is created")
    void anExpiredBudgetNeverStartsTheApplication() {
        LiveEvalActivation.Decision enabled = LiveEvalActivation.evaluate(
                activatedProperties(), validCredentials());
        LiveEvalCredentials.Report credentials = LiveEvalCredentials.inspect(validCredentials());

        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        Clock movable = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        LiveEvalBudget expired = new LiveEvalBudget(10, 10, 10, Duration.ofMinutes(1), movable);
        now[0] = now[0].plus(Duration.ofMinutes(2));

        LiveEvalPreflight.Plan plan = new LiveEvalPreflight.Plan(enabled, credentials, expired);

        assertThatThrownBy(() -> LiveEvalGuardedContext.create(plan, FakeApplication.class))
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("time budget");

        assertThat(FakeApplication.PROVIDER_INITIALIZATIONS).hasValue(0);
    }

    @Test
    @DisplayName("a valid plan starts the context and every consumer receives a budgeted model")
    void validConfigurationStartsTheApplicationAndBudgetsItsModels() {
        Map<String, String> properties = activatedProperties();
        properties.put(LiveEvalBudget.MAX_CHAT_CALLS_PROPERTY, "1");
        properties.put(LiveEvalBudget.MAX_EMBEDDING_CALLS_PROPERTY, "1");
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady(properties, validCredentials());

        try (ConfigurableApplicationContext context =
                     LiveEvalGuardedContext.create(plan, FakeApplication.class)) {

            assertThat(FakeApplication.PROVIDER_INITIALIZATIONS)
                    .as("the fake chat and embedding beans are constructed once each")
                    .hasValue(2);

            ChatModel chat = context.getBean(ChatModel.class);
            EmbeddingModel embedding = context.getBean(EmbeddingModel.class);

            assertThat(chat).isInstanceOf(BudgetedChatModel.class);
            assertThat(embedding).isInstanceOf(BudgetedEmbeddingModel.class);
            assertThat(FakeApplication.INJECTED_CHAT_MODEL)
                    .as("a consumer bean must be injected with the decorator, not the raw model")
                    .isSameAs(chat);

            chat.call(new Prompt("allowed chat call"));
            assertThat(FakeApplication.CHAT_DELEGATE_CALLS).hasValue(1);

            assertThatThrownBy(() -> chat.call(new Prompt("rejected chat call")))
                    .isInstanceOf(LiveEvalBudgetExceededException.class);
            assertThat(FakeApplication.CHAT_DELEGATE_CALLS)
                    .as("the rejected chat call never reached the provider bean")
                    .hasValue(1);

            embedding.call(new EmbeddingRequest(List.of("allowed text"), EmbeddingOptions.builder().build()));
            assertThat(FakeApplication.EMBEDDING_DELEGATE_CALLS).hasValue(1);

            assertThatThrownBy(() -> embedding.call(
                    new EmbeddingRequest(List.of("rejected text"), EmbeddingOptions.builder().build())))
                    .isInstanceOf(LiveEvalBudgetExceededException.class);
            assertThat(FakeApplication.EMBEDDING_DELEGATE_CALLS)
                    .as("the rejected embedding call never reached the provider bean")
                    .hasValue(1);

            assertThat(plan.budget().chatCalls()).isEqualTo(2);
            assertThat(plan.budget().embeddingCalls()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the post-processor never wraps an already budgeted model twice")
    void anAlreadyBudgetedModelIsNotWrappedTwice() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 10, Duration.ofMinutes(1));
        BudgetedModelBeanPostProcessor processor = new BudgetedModelBeanPostProcessor(budget);
        BudgetedChatModel wrapped = new BudgetedChatModel(new FakeApplication.FakeChatModel(), budget);

        assertThat(processor.postProcessAfterInitialization(wrapped, "chatModel")).isSameAs(wrapped);
        assertThat(processor.postProcessAfterInitialization("a plain string", "other"))
                .isEqualTo("a plain string");
    }

    private static Map<String, String> activatedProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put(LiveEvalActivation.PROFILE_PROPERTY, LiveEvalActivation.PROFILE_ACTIVE_VALUE);
        properties.put(LiveEvalActivation.OPT_IN_PROPERTY, "true");
        return properties;
    }

    private static Map<String, String> validCredentials() {
        Map<String, String> environment = new HashMap<>();
        environment.put(LiveEvalCredentials.GEMINI_VARIABLE, SYNTHETIC_GEMINI);
        environment.put(LiveEvalCredentials.GROQ_VARIABLE, SYNTHETIC_GROQ);
        return environment;
    }

    /**
     * A stand-in application with no auto-configuration: it can only construct
     * the two beans below, so "was a provider client created?" is answered by
     * counting constructor calls.
     */
    @Configuration(proxyBeanMethods = false)
    static class FakeApplication {

        static final AtomicInteger PROVIDER_INITIALIZATIONS = new AtomicInteger();
        static final AtomicInteger CHAT_DELEGATE_CALLS = new AtomicInteger();
        static final AtomicInteger EMBEDDING_DELEGATE_CALLS = new AtomicInteger();
        static volatile ChatModel INJECTED_CHAT_MODEL;

        static void reset() {
            PROVIDER_INITIALIZATIONS.set(0);
            CHAT_DELEGATE_CALLS.set(0);
            EMBEDDING_DELEGATE_CALLS.set(0);
            INJECTED_CHAT_MODEL = null;
        }

        @Bean
        ChatModel fakeChatModel() {
            PROVIDER_INITIALIZATIONS.incrementAndGet();
            return new FakeChatModel();
        }

        @Bean
        EmbeddingModel fakeEmbeddingModel() {
            PROVIDER_INITIALIZATIONS.incrementAndGet();
            return new FakeEmbeddingModel();
        }

        @Bean
        FakeConsumer fakeConsumer(ChatModel chatModel) {
            INJECTED_CHAT_MODEL = chatModel;
            return new FakeConsumer();
        }

        /** Records that a chat call arrived; never contacts anything. */
        static final class FakeChatModel implements ChatModel {

            @Override
            public ChatResponse call(Prompt prompt) {
                CHAT_DELEGATE_CALLS.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                CHAT_DELEGATE_CALLS.incrementAndGet();
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            }
        }

        /** Records that an embedding call arrived; never contacts anything. */
        static final class FakeEmbeddingModel implements EmbeddingModel {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                EMBEDDING_DELEGATE_CALLS.incrementAndGet();
                return new EmbeddingResponse(List.of(new Embedding(new float[] {1.0f}, 0)));
            }

            @Override
            public float[] embed(Document document) {
                EMBEDDING_DELEGATE_CALLS.incrementAndGet();
                return new float[] {1.0f};
            }

            @Override
            public int dimensions() {
                return 768;
            }
        }

        /** A consumer that only proves what it was injected with. */
        static final class FakeConsumer {
        }
    }
}
