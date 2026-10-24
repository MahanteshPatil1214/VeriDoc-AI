package com.veridoc.ai.evaluation.live;

import java.util.List;
import java.util.UUID;

import com.veridoc.ai.conversation.api.dto.ChatDtos.ChatResponseDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.CitationDto;
import com.veridoc.ai.conversation.application.ConversationService;
import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvidencePassage;
import com.veridoc.ai.evaluation.HistoryTurn;
import com.veridoc.ai.message.persistence.MessageRepository;
import com.veridoc.ai.rag.ChatService;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.support.RecordingPromptBuilder;

/**
 * Drives the production conversation and chat services for a live case.
 *
 * <p>Every case opens its own conversation and attaches the full corpus, so no
 * case can inherit another case's history. The exact evidence handed to the
 * model is captured by the test-only {@link RecordingPromptBuilder}; the
 * production chat service is unchanged.
 */
public final class SpringLiveEvalTransport implements LiveEvalTransport {

    private final AuthenticatedUser user;
    private final ConversationService conversations;
    private final ChatService chat;
    private final RecordingPromptBuilder prompts;
    private final MessageRepository messages;
    private final List<UUID> documentIds;

    public SpringLiveEvalTransport(AuthenticatedUser user,
                                   ConversationService conversations,
                                   ChatService chat,
                                   RecordingPromptBuilder prompts,
                                   MessageRepository messages,
                                   List<UUID> documentIds) {
        this.user = user;
        this.conversations = conversations;
        this.chat = chat;
        this.prompts = prompts;
        this.messages = messages;
        this.documentIds = List.copyOf(documentIds);
    }

    @Override
    public CaseSession openCase(EvalCase evalCase, EvalConversationPlanner.Plan plan) {
        UUID conversationId = conversations.create(user, plan.conversationKey()).id();
        conversations.attach(user, conversationId, documentIds);
        return new Session(conversationId);
    }

    private final class Session implements CaseSession {

        private final UUID conversationId;

        private Session(UUID conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public void replay(HistoryTurn turn, int expectedHistoryMessages) {
            int observed = historyCount();
            if (observed != expectedHistoryMessages) {
                throw new IllegalStateException("live case history mismatch before replay: saw "
                        + observed + " messages, expected " + expectedHistoryMessages);
            }
            prompts.reset();
            chat.ask(user, conversationId, turn.content());
        }

        @Override
        public LiveTurn askFinal(EvalCase evalCase) {
            int historyMessages = historyCount();
            prompts.reset();
            long started = System.nanoTime();
            ChatResponseDto response = chat.ask(user, conversationId, evalCase.question());
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;

            List<EvidencePassage> evidence = prompts.lastEvidence();
            List<EvidencePassage> citations = response.citations() == null ? List.of()
                    : response.citations().stream().map(SpringLiveEvalTransport::toPassage).toList();

            return new LiveTurn(evidence, citations, citations.isEmpty(), response.content(),
                    latencyMs, response.latencyMs(), response.promptTokens(),
                    response.completionTokens(), response.totalTokens(), historyMessages);
        }

        private int historyCount() {
            return Math.toIntExact(messages.countByConversationId(conversationId));
        }
    }

    private static EvidencePassage toPassage(CitationDto citation) {
        return new EvidencePassage(citation.documentFilename(), citation.chunkId(),
                citation.pageNumber());
    }
}
