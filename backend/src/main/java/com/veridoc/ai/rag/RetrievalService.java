package com.veridoc.ai.rag;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.stereotype.Service;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.config.properties.RetrievalProperties;
import com.veridoc.ai.conversation.persistence.ConversationDocumentRepository;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.infrastructure.vector.PgVectorChunkRepository;

/**
 * Authorized vector retrieval.
 *
 * <p>Ownership is enforced structurally: the conversation must belong to the
 * caller and only chunks of {@code READY} documents attached to that
 * conversation are searched — in the same SQL statement. No "retrieve all,
 * then filter" path exists.
 */
@Service
public class RetrievalService {

    private final ConversationRepository conversationRepository;
    private final ConversationDocumentRepository conversationDocumentRepository;
    private final PgVectorChunkRepository pgVectorChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties embeddingProperties;
    private final RetrievalProperties retrievalProperties;

    public RetrievalService(ConversationRepository conversationRepository,
                            ConversationDocumentRepository conversationDocumentRepository,
                            PgVectorChunkRepository pgVectorChunkRepository,
                            EmbeddingModel embeddingModel,
                            EmbeddingProperties embeddingProperties,
                            RetrievalProperties retrievalProperties) {
        this.conversationRepository = conversationRepository;
        this.conversationDocumentRepository = conversationDocumentRepository;
        this.pgVectorChunkRepository = pgVectorChunkRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = embeddingProperties;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * Retrieves the most similar chunks for {@code query} among the READY
     * documents attached to {@code conversationId}. Rows below the similarity
     * threshold are dropped so that an unsupported question yields no evidence
     * and the chat layer can respond with the canonical "no evidence" answer.
     */
    public List<RetrievedChunkRow> retrieve(UUID ownerId, UUID conversationId, String query) {
        conversationRepository.findByIdAndOwnerId(conversationId, ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));

        List<UUID> documentIds = conversationDocumentRepository.findDocumentIds(conversationId);
        if (documentIds.isEmpty()) {
            return List.of();
        }

        double[] queryVector = embedQuery(query);
        List<RetrievedChunkRow> candidates = pgVectorChunkRepository.similaritySearch(
                ownerId, documentIds, queryVector,
                embeddingProperties.model(), embeddingProperties.version(),
                retrievalProperties.candidateK());

        return candidates.stream()
                .filter(row -> row.similarity() >= retrievalProperties.minScore())
                .limit(retrievalProperties.topK())
                .toList();
    }

    private double[] embedQuery(String query) {
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(embeddingProperties.model())
                .dimensions(embeddingProperties.dimensions())
                .taskType(googleTaskType(embeddingProperties.queryTaskType()))
                .build();
        return toDoubles(embeddingModel.call(new EmbeddingRequest(List.of(query), options))
                .getResults().get(0).getOutput());
    }

    private static GoogleGenAiTextEmbeddingOptions.TaskType googleTaskType(String value) {
        try {
            return GoogleGenAiTextEmbeddingOptions.TaskType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown embedding task type: " + value, e);
        }
    }

    private static double[] toDoubles(float[] floats) {
        double[] out = new double[floats.length];
        for (int i = 0; i < floats.length; i++) {
            out[i] = floats[i];
        }
        return out;
    }
}