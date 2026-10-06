package com.veridoc.ai.document.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record UploadResponse(
            UUID id,
            String filename,
            String status,
            long sizeBytes
    ) {
    }

    public record DocumentListItem(
            UUID id,
            String filename,
            String status,
            Integer pageCount,
            Integer chunkCount,
            Instant createdAt
    ) {
    }

    public record DocumentStatusResponse(
            UUID id,
            String status,
            String stage,
            String error,
            Integer pageCount,
            Integer chunkCount
    ) {
    }
}