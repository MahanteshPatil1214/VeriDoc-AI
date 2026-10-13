package com.veridoc.ai.rag;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Service;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.config.properties.RetrievalProperties;
import com.veridoc.ai.conversation.persistence.ConversationDocumentRepository;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;
import com.veridoc.ai.infrastructure.config.EmbeddingOptionsFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationDocumentRepository conversationDocumentRepository;
    private final PgVectorChunkRepository pgVectorChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties embeddingProperties;
    private final RetrievalProperties retrievalProperties;
    private final QueryRewriteService queryRewriteService;

    public RetrievalService(ConversationRepository conversationRepository,
                            ConversationDocumentRepository conversationDocumentRepository,
                            PgVectorChunkRepository pgVectorChunkRepository,
                            EmbeddingModel embeddingModel,
                            EmbeddingProperties embeddingProperties,
                            RetrievalProperties retrievalProperties,
                            QueryRewriteService queryRewriteService) {
        this.conversationRepository = conversationRepository;
        this.conversationDocumentRepository = conversationDocumentRepository;
        this.pgVectorChunkRepository = pgVectorChunkRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = embeddingProperties;
        this.retrievalProperties = retrievalProperties;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * Retrieves the most similar chunks for {@code query} among the READY
     * documents attached to {@code conversationId}. Rows below the similarity
     * threshold are dropped so that an unsupported question yields no evidence
     * and the chat layer can respond with the canonical "no evidence" answer.
     */
    public List<RetrievedChunkRow> retrieve(UUID ownerId, UUID conversationId, String query) {
        return retrieve(ownerId, conversationId, query, true);
    }

    /**
     * @param rewrite when {@code false}, {@code query} is embedded verbatim — used
     *                by the RAG debugger to inspect retrieval under an already
     *                rewritten query without rewriting a second time.
     */
    public List<RetrievedChunkRow> retrieve(UUID ownerId, UUID conversationId, String query,
                                            boolean rewrite) {
        conversationRepository.findByIdAndOwnerId(conversationId, ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));

        List<UUID> documentIds = conversationDocumentRepository.findDocumentIds(conversationId);
        if (documentIds.isEmpty()) {
            log.info("retrieval.skip conversation={} reason=noDocuments", conversationId);
            return List.of();
        }

        long tw = System.currentTimeMillis();
        log.info("retrieval.rewrite.start conversation={} rewrite={}", conversationId, rewrite);
        String effectiveQuery = rewrite ? queryRewriteService.rewrite(query) : query;
        log.info("retrieval.rewrite.done elapsedMs={} changed={}",
                System.currentTimeMillis() - tw, !effectiveQuery.equals(query));

        long te = System.currentTimeMillis();
        double[] queryVector = embedQuery(effectiveQuery);
        log.info("retrieval.embedQuery.done elapsedMs={} dims={}",
                System.currentTimeMillis() - te, queryVector.length);

        long ts = System.currentTimeMillis();
        List<RetrievedChunkRow> candidates = pgVectorChunkRepository.similaritySearch(
                ownerId, documentIds, queryVector,
                embeddingProperties.model(), embeddingProperties.version(),
                retrievalProperties.candidateK());
        log.info("retrieval.search.done elapsedMs={} candidates={} docs={}",
                System.currentTimeMillis() - ts, candidates.size(), documentIds.size());

        return candidates.stream()
                .filter(row -> row.similarity() >= retrievalProperties.minScore())
                .limit(retrievalProperties.topK())
                .toList();
    }

    private double[] embedQuery(String query) {
        return toDoubles(embeddingModel.call(
                        new EmbeddingRequest(List.of(query),
                                EmbeddingOptionsFactory.forQuery(embeddingProperties)))
                .getResults().get(0).getOutput());
    }

    private static double[] toDoubles(float[] floats) {
        double[] out = new double[floats.length];
        for (int i = 0; i < floats.length; i++) {
            out[i] = floats[i];
        }
        return out;
    }
}