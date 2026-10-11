package com.veridoc.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.RagProperties;
import com.veridoc.ai.conversation.domain.Conversation;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.rag.api.dto.RagDebugDtos.RagDebugEvidence;
import com.veridoc.ai.rag.api.dto.RagDebugDtos.RagDebugResponse;

/**
 * Admin-only, read-only view of one retrieval pass: the rewritten query, the
 * passages that passed the similarity threshold (with scores), and the exact
 * grounding prompt. Nothing is persisted. Disabled unless
 * {@code veridoc.rag.debug-enabled=true} so it cannot leak document text in a
 * hardened deployment.
 */
@Service
public class RagDebugService {

    private final ConversationRepository conversationRepository;
    private final RetrievalService retrievalService;
    private final QueryRewriteService queryRewriteService;
    private final GroundedPromptBuilder promptBuilder;
    private final RagProperties ragProperties;

    public RagDebugService(ConversationRepository conversationRepository,
                           RetrievalService retrievalService,
                           QueryRewriteService queryRewriteService,
                           GroundedPromptBuilder promptBuilder,
                           RagProperties ragProperties) {
        this.conversationRepository = conversationRepository;
        this.retrievalService = retrievalService;
        this.queryRewriteService = queryRewriteService;
        this.promptBuilder = promptBuilder;
        this.ragProperties = ragProperties;
    }

    public RagDebugResponse debug(UUID conversationId, String question) {
        if (!ragProperties.debugEnabled()) {
            throw new AppException(ErrorCode.NOT_FOUND, "The RAG debugger is disabled");
        }
        if (question == null || question.isBlank()) {
            throw AppException.invalidRequest("question must not be blank");
        }
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound(
                        ErrorCode.CONVERSATION_NOT_FOUND, "Conversation", conversationId));

        String rewritten = queryRewriteService.rewrite(question);
        List<RetrievedChunkRow> evidence = retrievalService.retrieve(
                conversation.getOwnerId(), conversationId, rewritten, false);

        if (evidence.isEmpty()) {
            return new RagDebugResponse(question, rewritten, true, List.of(), null);
        }

        String systemPrompt = null;
        Prompt prompt = promptBuilder.build(question, evidence, List.of()).prompt();
        if (prompt.getSystemMessage() != null) {
            systemPrompt = prompt.getSystemMessage().getText();
        }

        List<RagDebugEvidence> debugEvidence = new ArrayList<>(evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunkRow row = evidence.get(i);
            debugEvidence.add(new RagDebugEvidence(i + 1, row.chunkId(), row.documentId(),
                    row.filename(), row.pageNumber(), row.section(), row.similarity(), row.content()));
        }
        return new RagDebugResponse(question, rewritten, false, debugEvidence, systemPrompt);
    }
}