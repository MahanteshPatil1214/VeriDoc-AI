package com.veridoc.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.veridoc.ai.config.properties.RagProperties;

import reactor.core.publisher.Flux;

/**
 * Query rewriting gate: only an enabled flag turns the model call on, and only
 * a clean, reasonably short answer is accepted — everything else falls back to
 * the original question so retrieval can never be starved.
 */
class QueryRewriteServiceTest {

    private static final String QUESTION = "What does placement entail?";
    private static final String NO_EVIDENCE =
            "I couldn't find enough information in the uploaded documents to answer that.";

    private static RagProperties rags(boolean rewriting) {
        return new RagProperties(8, 12000, rewriting, NO_EVIDENCE, false);
    }

    @Test
    void rewritingIsSkippedWhenDisabled() {
        StubChatModel stub = new StubChatModel("ignored");
        QueryRewriteService service = new QueryRewriteService(stub, rags(false));

        assertThat(service.rewrite(QUESTION)).isEqualTo(QUESTION);
        assertThat(stub.calls).isZero();
    }

    @Test
    void rewriteResultIsReturnedWhenEnabledAndThePromptAsksForAStandaloneQuery() {
        StubChatModel stub = new StubChatModel("placement steps across departments");
        QueryRewriteService service = new QueryRewriteService(stub, rags(true));

        assertThat(service.rewrite(QUESTION)).isEqualTo("placement steps across departments");
        assertThat(stub.calls).isEqualTo(1);
        assertThat(stub.lastSystem).contains("standalone search query");
    }

    @Test
    void verbalPreambleIsStripped() {
        StubChatModel stub = new StubChatModel("Query: placement steps across departments");
        QueryRewriteService service = new QueryRewriteService(stub, rags(true));

        assertThat(service.rewrite(QUESTION)).isEqualTo("placement steps across departments");
    }

    @Test
    void blankRewriteFallsBackToOriginalQuestion() {
        QueryRewriteService service = new QueryRewriteService(new StubChatModel("   "), rags(true));

        assertThat(service.rewrite(QUESTION)).isEqualTo(QUESTION);
    }

    @Test
    void implausiblyLongRewriteFallsBackToOriginalQuestion() {
        QueryRewriteService service =
                new QueryRewriteService(new StubChatModel("a".repeat(501)), rags(true));

        assertThat(service.rewrite(QUESTION)).isEqualTo(QUESTION);
    }

    private static final class StubChatModel implements ChatModel {

        private final String answer;
        private String lastSystem;
        private int calls;

        private StubChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastSystem = prompt.getSystemMessage() == null
                    ? "" : prompt.getSystemMessage().getText();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}