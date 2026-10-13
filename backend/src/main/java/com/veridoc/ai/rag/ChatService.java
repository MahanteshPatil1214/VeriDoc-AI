package com.veridoc.ai.rag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.veridoc.ai.citation.domain.Citation;
import com.veridoc.ai.citation.persistence.CitationRepository;
import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.RagProperties;
import com.veridoc.ai.config.properties.RateLimitProperties;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ChatResponseDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.CitationDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.MessageDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamCitations;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamError;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamToken;
import com.veridoc.ai.conversation.domain.Conversation;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.infrastructure.config.AiExceptions;
import com.veridoc.ai.message.domain.Message;
import com.veridoc.ai.message.domain.MessageRole;
import com.veridoc.ai.message.persistence.MessageRepository;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Flux;

/**
 * One grounding-conversation turn. The user's question is archived, evidence is
 * retrieved under ownership, the assistant's answer is generated only from that
 * evidence, and the exact citations are recorded next to the answer.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final CitationRepository citationRepository;
    private final RetrievalService retrievalService;
    private final GroundedPromptBuilder promptBuilder;
    private final ChatModel chatModel;
    private final RagProperties ragProperties;
    private final AiProperties aiProperties;
    private final com.veridoc.ai.security.RedisRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final MeterRegistry meterRegistry;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       CitationRepository citationRepository,
                       RetrievalService retrievalService,
                       GroundedPromptBuilder promptBuilder,
                       ChatModel chatModel,
                       RagProperties ragProperties,
                       AiProperties aiProperties,
                       com.veridoc.ai.security.RedisRateLimiter rateLimiter,
                       RateLimitProperties rateLimitProperties,
                       MeterRegistry meterRegistry) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
        this.aiProperties = aiProperties;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.meterRegistry = meterRegistry;
    }

    public ChatResponseDto ask(AuthenticatedUser user, UUID conversationId, String question) {
        long t0 = System.currentTimeMillis();
        log.info("chat.ask.start conversation={} questionChars={} rewriting={}",
                conversationId, question.length(), ragProperties.enableQueryRewriting());
        checkChatBudget(user);
        log.info("chat.ask.afterRateLimit +{}ms", System.currentTimeMillis() - t0);
        Conversation conversation = requireConversation(user, conversationId);
        autoTitle(conversation, question);

        List<org.springframework.ai.chat.messages.Message> history = history(conversationId);
        log.info("chat.ask.afterHistory +{}ms historyMessages={}",
                System.currentTimeMillis() - t0, history.size());

        Message userMessage = Message.user(UUID.randomUUID(), conversationId, question);
        messageRepository.save(userMessage);

        long tre = System.currentTimeMillis();
        log.info("chat.ask.retrieve.start +{}ms", tre - t0);
        List<RetrievedChunkRow> evidence = retrieve(user.userId(), conversationId, question);
        log.info("chat.ask.retrieve.done +{}ms elapsedMs={} evidence={}",
                System.currentTimeMillis() - t0, System.currentTimeMillis() - tre, evidence.size());

        Instant started = Instant.now();
        Message assistant;
        List<CitationDto> citationDtos;
        if (evidence.isEmpty()) {
            meterRegistry.counter("veridoc.rag.refusals").increment();
            assistant = completeAssistant(conversationId, null, ragProperties.noEvidenceAnswer(),
                    null, null, 0L);
            messageRepository.save(assistant);
            citationDtos = List.of();
            log.info("chat.ask.refused +{}ms", System.currentTimeMillis() - t0);
        } else {
            long tp = System.currentTimeMillis();
            GroundedPromptBuilder.PromptBundle bundle =
                    promptBuilder.build(question, evidence, history);
            log.info("chat.ask.prompt.built +{}ms elapsedMs={} evidenceUsed={} promptMessages={}",
                    System.currentTimeMillis() - t0, System.currentTimeMillis() - tp,
                    bundle.evidence().size(), bundle.prompt().getInstructions().size());
            debugEvidence(question, bundle.evidence());
            meterRegistry.counter("veridoc.rag.citations").increment(bundle.evidence().size());
            log.info("chat.ask.generate.start +{}ms", System.currentTimeMillis() - t0);
            assistant = generate(conversationId, bundle, started);
            log.info("chat.ask.generate.done +{}ms", System.currentTimeMillis() - t0);
            citationDtos = persistCitations(assistant.getId(), bundle.evidence());
        }
        log.info("chat.ask.done +{}ms modelMs={}", System.currentTimeMillis() - t0,
                Duration.between(started, Instant.now()).toMillis());
        return toResponse(assistant, citationDtos);
    }

    /**
     * Streaming variant of {@link #ask}. Ownership, retrieval and the user-message
     * archive happen synchronously before the response body starts so that auth
     * failures surface as normal HTTP errors rather than mid-stream events.
     *
     * <p>The assistant row is created up-front with status {@code STREAMING} and is
     * reconciled to {@code COMPLETE}, {@code FAILED} or {@code CANCELLED} exactly
     * once — on completion, on provider error, or on client disconnect
     * respectively. A partially streamed answer is still persisted.
     */
public Flux<ServerSentEvent<Object>> stream(AuthenticatedUser user,
                                                UUID conversationId,
                                                String question) {
        if (question == null || question.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "message must not be blank");
        }
        checkChatBudget(user);
        Conversation conversation = requireConversation(user, conversationId);
        autoTitle(conversation, question);

        List<org.springframework.ai.chat.messages.Message> history = history(conversationId);
        messageRepository.save(Message.user(UUID.randomUUID(), conversationId, question));

List<RetrievedChunkRow> evidence = retrieve(user.userId(), conversationId, question);

        if (evidence.isEmpty()) {
            meterRegistry.counter("veridoc.rag.refusals").increment();
            Message assistant = completeAssistant(conversationId, null,
                    ragProperties.noEvidenceAnswer(), null, null, 0L);
            messageRepository.save(assistant);
            ChatResponseDto done = toResponse(assistant, List.of());
            return Flux.just(
                    sse("token", new StreamToken(assistant.getContent())),
                    sse("done", done));
        }

GroundedPromptBuilder.PromptBundle bundle =
                promptBuilder.build(question, evidence, history);
        debugEvidence(question, bundle.evidence());
        meterRegistry.counter("veridoc.rag.citations").increment(bundle.evidence().size());
        Instant started = Instant.now();
        Message assistant = Message.assistantStreaming(
                UUID.randomUUID(), conversationId, aiProperties.chatModel());
        messageRepository.save(assistant);

        StringBuilder buffer = new StringBuilder();
        AtomicReference<ChatResponse> last = new AtomicReference<>();

        return chatModel.stream(bundle.prompt())
                .doOnNext(last::set)
                .map(ChatService::chunkText)
                .filter(text -> !text.isEmpty())
                .doOnNext(buffer::append)
                .map(text -> sse("token", new StreamToken(text)))
                .concatWith(Flux.defer(() -> completeStream(
                        assistant, buffer.toString(), last.get(), bundle.evidence(), started)))
                .onErrorResume(error -> failStream(assistant, buffer.toString(), error, started))
                .doOnCancel(() -> cancelStream(assistant, buffer.toString(), started));
    }

    public List<MessageDto> messages(AuthenticatedUser user, UUID conversationId) {
        requireConversation(user, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(
                        conversationId, Pageable.unpaged()).stream()
                .filter(m -> m.getRole() != MessageRole.SYSTEM)
                .map(ChatService::toMessageDto)
                .toList();
    }

private Conversation requireConversation(AuthenticatedUser user, UUID conversationId) {
        return conversationRepository.findByIdAndOwnerId(conversationId, user.userId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));
    }

    /** Retrieval timed into {@code veridoc.rag.retrieval}, regardless of outcome. */
    private List<RetrievedChunkRow> retrieve(UUID ownerId, UUID conversationId, String question) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return retrievalService.retrieve(ownerId, conversationId, question);
        } finally {
            sample.stop(meterRegistry.timer("veridoc.rag.retrieval"));
        }
    }

    /** With {@code veridoc.rag.debug-enabled=true}, log exactly what fed the model. */
    private void debugEvidence(String question, List<RetrievedChunkRow> evidence) {
        if (!ragProperties.debugEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder("RAG retrieved ").append(evidence.size())
                .append(" passage(s) for \"").append(question).append("\":");
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunkRow c = evidence.get(i);
            sb.append("\n  [").append(i + 1).append("] score=").append(c.similarity())
                    .append(" doc=").append(c.filename()).append(" chunk=").append(c.chunkId())
                    .append(" section=").append(c.section())
                    .append(" starts: ").append(abbreviate(c.content(), 120));
        }
        log.debug(sb.toString());
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

private void autoTitle(Conversation conversation, String question) {
        if (conversation.getTitle().equals("New chat")) {
            conversation.autoTitleFrom(question);
            conversationRepository.save(conversation);
        }
    }

    private void checkChatBudget(AuthenticatedUser user) {
        rateLimiter.check("chat", user.userId().toString(), rateLimitProperties.chat());
    }

    private Message generate(UUID conversationId, GroundedPromptBuilder.PromptBundle bundle,
                             Instant started) {
        long tg = System.currentTimeMillis();
        log.info("chat.generate.modelCall.start conversation={} model={} promptMessages={}",
                conversationId, aiProperties.chatModel(), bundle.prompt().getInstructions().size());
        ChatResponse response;
        try {
            response = chatModel.call(bundle.prompt());
        } catch (RuntimeException e) {
            log.error("chat.generate.modelCall.failed after {}ms conversation={} type={} message={}",
                    System.currentTimeMillis() - tg, conversationId,
                    e.getClass().getName(), e.getMessage());
            throw toProviderFailure(e);
        }
        log.info("chat.generate.modelCall.done elapsedMs={}", System.currentTimeMillis() - tg);
        String answer = response.getResult().getOutput().getText();
        long latencyMs = Duration.between(started, Instant.now()).toMillis();

        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();

        Message assistant = completeAssistant(conversationId, aiProperties.chatModel(),
                answer, promptTokens, completionTokens, latencyMs);
        messageRepository.save(assistant);

        log.info("Assistant message {} for conversation {}: {} chars, {} evidence passages, {} ms",
                assistant.getId(), conversationId, answer.length(),
                bundle.evidence().size(), latencyMs);
        return assistant;
    }

    /** Last {@code rag.history-messages} turns, oldest first, to give context. */
    private List<org.springframework.ai.chat.messages.Message> history(UUID conversationId) {
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(
                conversationId, Pageable.unpaged());
        int skip = Math.max(0, all.size() - ragProperties.historyMessages());
        List<Message> recent = new ArrayList<>(all.subList(skip, all.size()));
        List<org.springframework.ai.chat.messages.Message> history =
                new ArrayList<>(recent.size());
        for (Message m : recent) {
            if (m.getRole() == MessageRole.USER) {
                history.add(new UserMessage(m.getContent()));
            } else if (m.getRole() == MessageRole.ASSISTANT) {
                history.add(new AssistantMessage(m.getContent()));
            }
        }
        return history;
    }

    private Message completeAssistant(UUID conversationId, String model, String content,
                                      Integer promptTokens, Integer completionTokens,
                                      Long latencyMs) {
        Message assistant = Message.assistantStreaming(UUID.randomUUID(), conversationId, model);
        assistant.complete(content, promptTokens, completionTokens, latencyMs);
        return assistant;
    }

    private List<CitationDto> persistCitations(UUID messageId, List<RetrievedChunkRow> evidence) {
        List<CitationDto> dtos = new ArrayList<>(evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunkRow chunk = evidence.get(i);
            Citation citation = Citation.create(UUID.randomUUID(), messageId, i + 1,
                    chunk.documentId(), chunk.filename(), chunk.chunkId(),
                    chunk.pageNumber(), chunk.section(), chunk.similarity(), chunk.content());
            citationRepository.save(citation);
            dtos.add(new CitationDto(i + 1, chunk.documentId(), chunk.filename(), chunk.chunkId(),
                    chunk.pageNumber(), chunk.section(), chunk.similarity(), chunk.content()));
        }
        return dtos;
    }

    private static ChatResponseDto toResponse(Message message, List<CitationDto> citations) {
        return new ChatResponseDto(message.getId(), message.getConversationId(),
                message.getRole().name(), message.getContent(), message.getStatus().name(),
                message.getModel(), message.getPromptTokens(), message.getCompletionTokens(),
                message.getTotalTokens(), message.getLatencyMs(), message.getCreatedAt(), citations);
    }

    private static MessageDto toMessageDto(Message message) {
        return new MessageDto(message.getId(), message.getRole().name(), message.getContent(),
                message.getStatus().name(), message.getCreatedAt());
    }

    private Flux<ServerSentEvent<Object>> completeStream(Message assistant, String answer,
                                                         ChatResponse last,
                                                         List<RetrievedChunkRow> evidence,
                                                         Instant started) {
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        Usage usage = usageOf(last);
        assistant.complete(answer,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                latencyMs);
        messageRepository.save(assistant);

        List<CitationDto> citations = persistCitations(assistant.getId(), evidence);
        log.info("Streamed assistant message {} for conversation {}: {} chars, {} evidence passages, {} ms",
                assistant.getId(), assistant.getConversationId(), answer.length(),
                evidence.size(), latencyMs);
        return Flux.just(
                sse("citations", new StreamCitations(citations)),
                sse("done", toResponse(assistant, citations)));
    }

    private Flux<ServerSentEvent<Object>> failStream(Message assistant, String partial,
                                                     Throwable error, Instant started) {
        ErrorCode code = errorCodeOf(error);
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        log.error("Streamed answer failed for message {} code={}", assistant.getId(), code, error);

        assistant.complete(partial, null, null, latencyMs);
        assistant.fail(code.name());
        messageRepository.save(assistant);

        return Flux.just(sse("error", new StreamError(code.name(),
                "The answer could not be generated. Please try again.")));
    }

    private void cancelStream(Message assistant, String partial, Instant started) {
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        log.info("Streamed answer cancelled for message {} after {} chars",
                assistant.getId(), partial.length());

        assistant.complete(partial, null, null, latencyMs);
        assistant.cancel();
        messageRepository.save(assistant);
    }

    private static ServerSentEvent<Object> sse(String event, Object data) {
        return ServerSentEvent.<Object>builder().event(event).data(data).build();
    }

    private static String chunkText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static ErrorCode errorCodeOf(Throwable error) {
        if (error instanceof AppException app) {
            return app.code();
        }
        return AiExceptions.classify(error);
    }

    /**
     * Maps a provider failure to a stable, user-safe error. An
     * {@link AppException} raised below is passed through unchanged; anything
     * else (a bounded timeout, a rate limit, a 5xx) is classified by
     * {@link AiExceptions} so the caller sees {@code LLM_TIMEOUT},
     * {@code LLM_RATE_LIMITED} or {@code LLM_UNAVAILABLE} rather than a generic
     * 500.
     */
    private static AppException toProviderFailure(RuntimeException error) {
        if (error instanceof AppException app) {
            return app;
        }
        return new AppException(AiExceptions.classify(error),
                "The answer could not be generated. Please try again.", error);
    }
}
