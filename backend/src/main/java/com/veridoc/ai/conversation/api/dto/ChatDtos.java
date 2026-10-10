package com.veridoc.ai.conversation.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ConversationDocumentRef(UUID id, String filename) {
    }

    public record CreateConversationRequest(
            @Size(max = 255) String title
    ) {
    }

    public record AttachDocumentsRequest(
            @NotEmpty List<UUID> documentIds
    ) {
    }

    public record ChatRequest(
            @NotBlank @Size(max = 8000) String message
    ) {
    }

    public record ConversationResponse(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<ConversationDocumentRef> documents
    ) {
    }

    public record ConversationListItem(
            UUID id,
            String title,
            Instant updatedAt
    ) {
    }

    public record MessageDto(
            UUID id,
            String role,
            String content,
            String status,
            Instant createdAt
    ) {
    }

    public record CitationDto(
            int ordinal,
            UUID documentId,
            String documentFilename,
            UUID chunkId,
            Integer pageNumber,
            String section,
            Double relevanceScore,
            String quotedText
    ) {
    }

    public record ChatResponseDto(
            UUID messageId,
            UUID conversationId,
            String role,
            String content,
            String status,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long latencyMs,
            Instant createdAt,
            List<CitationDto> citations
    ) {
    }

    /** SSE {@code token} event: an incremental piece of the assistant answer. */
    public record StreamToken(String text) {
    }

    /** SSE {@code citations} event: the sources the completed answer was grounded in. */
    public record StreamCitations(List<CitationDto> citations) {
    }

    /** SSE {@code error} event: a controlled failure with a stable error code. */
    public record StreamError(String code, String message) {
    }
}