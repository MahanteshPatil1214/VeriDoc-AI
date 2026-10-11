package com.veridoc.ai.rag.api.dto;

import java.util.List;
import java.util.UUID;

/** Admin-facing RAG debugger payload: exactly what retrieval fed the model. */
public final class RagDebugDtos {

    private RagDebugDtos() {
    }

    public record RagDebugEvidence(
            int ordinal,
            UUID chunkId,
            UUID documentId,
            String documentFilename,
            Integer pageNumber,
            String section,
            double relevanceScore,
            String content
    ) {
    }

    public record RagDebugResponse(
            String question,
            String rewrittenQuery,
            boolean refusal,
            List<RagDebugEvidence> evidence,
            String systemPrompt
    ) {
    }
}