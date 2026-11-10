package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

import reactor.core.publisher.Flux;

/**
 * Behaviour of the budgeted model decorators.
 *
 * <p>Each test asks a recording delegate whether it was reached. The point of
 * every rejection test is the same: the decorator throws <em>before</em> the
 * delegate is invoked, so no request could ever leave the process.
 *
 * <p>No test needs a credential, a container or a network connection.
 */
class LiveEvalBudgetedModelsTest {

    @Test
    @DisplayName("chat: the cap is allowed, the next call is rejected and never reaches the delegate")
    void chatCapRejectsBeforeTheDelegate() {
        RecordingChatModel delegate = new RecordingChatModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 1, 10, Duration.ofMinutes(1));
        BudgetedChatModel model = new BudgetedChatModel(delegate, budget);

        model.call(new Prompt("first allowed call"));

        assertThat(delegate.calls()).isEqualTo(1);

        assertThatThrownBy(() -> model.call(new Prompt("second rejected call")))
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("before the provider was contacted");

        assertThat(delegate.calls())
                .as("the rejected call must never reach the real model")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("chat: streaming counts as one chat call and is rejected at the cap")
    void streamingCountsAsOneChatCall() {
        RecordingChatModel delegate = new RecordingChatModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 1, 10, Duration.ofMinutes(1));
        BudgetedChatModel model = new BudgetedChatModel(delegate, budget);

        Flux<ChatResponse> stream = model.stream(new Prompt("allowed stream"));

        assertThat(stream).isNotNull();
        assertThat(delegate.streams()).isEqualTo(1);
        assertThat(budget.chatCalls()).isEqualTo(1);

        assertThatThrownBy(() -> model.stream(new Prompt("rejected stream")))
                .isInstanceOf(LiveEvalBudgetExceededException.class);
        assertThat(delegate.streams()).isEqualTo(1);
    }

    @Test
    @DisplayName("chat: a delegate failure still consumes the slot, so a retry cannot be free")
    void aFailingCallStillConsumesTheSlot() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("provider refused");
        };
        LiveEvalBudget budget = new LiveEvalBudget(10, 1, 10, Duration.ofMinutes(1));
        BudgetedChatModel model = new BudgetedChatModel(failing, budget);

        assertThatThrownBy(() -> model.call(new Prompt("boom")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(budget.chatCalls()).isEqualTo(1);
        assertThatThrownBy(() -> model.call(new Prompt("retry")))
                .isInstanceOf(LiveEvalBudgetExceededException.class);
    }

    @Test
    @DisplayName("embedding: the cap is allowed, the next call is rejected and never reaches the delegate")
    void embeddingCapRejectsBeforeTheDelegate() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 1, Duration.ofMinutes(1));
        BudgetedEmbeddingModel model = new BudgetedEmbeddingModel(delegate, budget);

        model.call(new EmbeddingRequest(List.of("first allowed text"), EmbeddingOptions.builder().build()));

        assertThat(delegate.requests()).isEqualTo(1);

        assertThatThrownBy(() -> model.call(
                new EmbeddingRequest(List.of("second rejected text"), EmbeddingOptions.builder().build())))
                .isInstanceOf(LiveEvalBudgetExceededException.class)
                .hasMessageContaining("embedding")
                .hasMessageContaining("before the provider was contacted");

        assertThat(delegate.requests())
                .as("the rejected embedding must never reach the real model")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("embedding: embed(Document) is charged once and rejected at the cap")
    void documentEmbeddingIsCharged() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 1, Duration.ofMinutes(1));
        BudgetedEmbeddingModel model = new BudgetedEmbeddingModel(delegate, budget);

        model.embed(new Document("allowed document"));

        assertThat(delegate.documentEmbeds()).isEqualTo(1);
        assertThat(budget.embeddingCalls()).isEqualTo(1);

        assertThatThrownBy(() -> model.embed(new Document("rejected document")))
                .isInstanceOf(LiveEvalBudgetExceededException.class);
        assertThat(delegate.documentEmbeds()).isEqualTo(1);
    }

    @Test
    @DisplayName("embedding: embed(String) funnels through call(...) and is charged exactly once")
    void stringEmbeddingIsChargedExactlyOnce() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 5, Duration.ofMinutes(1));
        BudgetedEmbeddingModel model = new BudgetedEmbeddingModel(delegate, budget);

        float[] vector = model.embed("some query text");

        assertThat(vector).hasSize(1);
        assertThat(delegate.requests()).isEqualTo(1);
        assertThat(budget.embeddingCalls())
                .as("a default method that delegates internally must not be double-charged")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("embedding: embedForResponse(...) is charged exactly once")
    void embedForResponseIsChargedExactlyOnce() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 5, Duration.ofMinutes(1));
        BudgetedEmbeddingModel model = new BudgetedEmbeddingModel(delegate, budget);

        EmbeddingResponse response = model.embedForResponse(List.of("a", "b"));

        assertThat(response).isNotNull();
        assertThat(delegate.requests()).isEqualTo(1);
        assertThat(budget.embeddingCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("embedding: dimensions() is metadata and costs no budget slot")
    void dimensionsIsNotCharged() {
        RecordingEmbeddingModel delegate = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 1, Duration.ofMinutes(1));
        BudgetedEmbeddingModel model = new BudgetedEmbeddingModel(delegate, budget);

        assertThat(model.dimensions()).isEqualTo(768);

        assertThat(delegate.dimensionsCalls()).isEqualTo(1);
        assertThat(budget.embeddingCalls()).isZero();
    }

    @Test
    @DisplayName("the decorators expose the delegate they wrap, and never twice")
    void delegatesAreExposedAndNotNested() {
        RecordingChatModel chat = new RecordingChatModel();
        RecordingEmbeddingModel embedding = new RecordingEmbeddingModel();
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 10, Duration.ofMinutes(1));

        BudgetedChatModel chatModel = new BudgetedChatModel(chat, budget);
        BudgetedEmbeddingModel embeddingModel = new BudgetedEmbeddingModel(embedding, budget);

        assertThat(chatModel.delegate()).isSameAs(chat);
        assertThat(embeddingModel.delegate()).isSameAs(embedding);
        assertThat(chatModel.toString()).contains("chat 0/10");
        assertThat(embeddingModel.toString()).contains("embedding 0/10");
    }

    @Test
    @DisplayName("a null delegate or budget is rejected at construction, not at call time")
    void nullCollaboratorsAreRejected() {
        LiveEvalBudget budget = new LiveEvalBudget(10, 10, 10, Duration.ofMinutes(1));
        RecordingChatModel chat = new RecordingChatModel();
        RecordingEmbeddingModel embedding = new RecordingEmbeddingModel();

        assertThatThrownBy(() -> new BudgetedChatModel(null, budget))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BudgetedChatModel(chat, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BudgetedEmbeddingModel(null, budget))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BudgetedEmbeddingModel(embedding, null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Counts reachability without ever contacting anything. */
    private static final class RecordingChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger streams = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        }

        int calls() {
            return calls.get();
        }

        int streams() {
            return streams.get();
        }
    }

    /** Counts reachability without ever contacting anything. */
    private static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger documentEmbeds = new AtomicInteger();
        private final AtomicInteger dimensionsCalls = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            requests.incrementAndGet();
            return new EmbeddingResponse(List.of(new Embedding(new float[] {1.0f}, 0)));
        }

        @Override
        public float[] embed(Document document) {
            documentEmbeds.incrementAndGet();
            return new float[] {1.0f};
        }

        @Override
        public int dimensions() {
            dimensionsCalls.incrementAndGet();
            return 768;
        }

        int requests() {
            return requests.get();
        }

        int documentEmbeds() {
            return documentEmbeds.get();
        }

        int dimensionsCalls() {
            return dimensionsCalls.get();
        }
    }
}
