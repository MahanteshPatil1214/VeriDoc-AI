package com.veridoc.ai.rag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.veridoc.ai.conversation.api.dto.ChatDtos.ChatResponseDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.CitationDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.MessageDto;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamCitations;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamError;
import com.veridoc.ai.conversation.api.dto.ChatDtos.StreamToken;
import com.veridoc.ai.conversation.domain.Conversation;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.message.domain.Message;
import com.veridoc.ai.message.domain.MessageRole;
import com.veridoc.ai.message.persistence.MessageRepository;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

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

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       CitationRepository citationRepository,
                       RetrievalService retrievalService,
                       GroundedPromptBuilder promptBuilder,
                       ChatModel chatModel,
                       RagProperties ragProperties,
                       AiProperties aiProperties) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
        this.aiProperties = aiProperties;
    }

    public ChatResponseDto ask(AuthenticatedUser user, UUID conversationId, String question) {
        Conversation conversation = requireConversation(user, conversationId);
        autoTitle(conversation, question);

        List<org.springframework.ai.chat.messages.Message> history = history(conversationId);

        Message userMessage = Message.user(UUID.randomUUID(), conversationId, question);
        messageRepository.save(userMessage);

        List<RetrievedChunkRow> evidence =
                retrievalService.retrieve(user.userId(), conversationId, question);

        Instant started = Instant.now();
        Message assistant;
        List<CitationDto> citationDtos;
        if (evidence.isEmpty()) {
            assistant = completeAssistant(conversationId, null, ragProperties.noEvidenceAnswer(),
                    null, null, 0L);
            messageRepository.save(assistant);
            citationDtos = List.of();
        } else {
            assistant = generate(conversationId, question, evidence, history, started);
            citationDtos = persistCitations(assistant.getId(), evidence);
        }
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
        Conversation conversation = requireConversation(user, conversationId);
        autoTitle(conversation, question);

        List<org.springframework.ai.chat.messages.Message> history = history(conversationId);
        messageRepository.save(Message.user(UUID.randomUUID(), conversationId, question));

        List<RetrievedChunkRow> evidence =
                retrievalService.retrieve(user.userId(), conversationId, question);

        if (evidence.isEmpty()) {
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
                        assistant, buffer.toString(), last.get(), evidence, started)))
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

    private void autoTitle(Conversation conversation, String question) {
        if (conversation.getTitle().equals("New chat")) {
            conversation.autoTitleFrom(question);
            conversationRepository.save(conversation);
        }
    }

    private Message generate(UUID conversationId, String question,
                             List<RetrievedChunkRow> evidence,
                             List<org.springframework.ai.chat.messages.Message> history,
                             Instant started) {
        GroundedPromptBuilder.PromptBundle bundle = promptBuilder.build(
                question, evidence, history);

        ChatResponse response = chatModel.call(bundle.prompt());
        String answer = response.getResult().getOutput().getText();
        long latencyMs = Duration.between(started, Instant.now()).toMillis();

        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();

        Message assistant = completeAssistant(conversationId, aiProperties.chatModel(),
                answer, promptTokens, completionTokens, latencyMs);
        messageRepository.save(assistant);

        log.info("Assistant message {} for conversation {}: {} chars, {} evidence passages, {} ms",
                assistant.getId(), conversationId, answer.length(), evidence.size(), latencyMs);
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
        String type = error.getClass().getName().toLowerCase(Locale.ROOT);
        String message = error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (type.contains("timeout") || message.contains("timeout") || message.contains("timed out")) {
            return ErrorCode.LLM_TIMEOUT;
        }
        if (message.contains("rate") || message.contains("quota") || message.contains("429")) {
            return ErrorCode.LLM_RATE_LIMITED;
        }
        if (type.contains("resourceaccess") || message.contains("unavailable")
                || message.contains("503")) {
            return ErrorCode.LLM_UNAVAILABLE;
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
