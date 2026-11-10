package com.veridoc.ai.rag;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * Authorized hybrid retrieval: vector (pgvector cosine) + keyword
 * (PostgreSQL tsvector / ts_rank_cd, i.e. BM25-equivalent).
 *
 * <p>Ownership is enforced structurally: the conversation must belong to the
 * caller and only chunks of {@code READY} documents attached to that
 * conversation are searched — in the same SQL statement. No "retrieve all,
 * then filter" path exists.
 *
 * <p>When {@code veridoc.retrieval.keyword-enabled} is {@code true}, results
 * from both searches are merged by chunk id and re-ranked using a weighted
 * combination of cosine similarity and keyword rank:
 * {@code hybridScore = vectorWeight * similarity + keywordWeight * keywordRank}.
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
     * Retrieves the most relevant chunks for {@code query} among the READY
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
        List<RetrievedChunkRow> vectorCandidates = pgVectorChunkRepository.similaritySearch(
                ownerId, documentIds, queryVector,
                embeddingProperties.model(), embeddingProperties.version(),
                retrievalProperties.candidateK());

        List<RetrievedChunkRow> keywordCandidates = List.of();
        if (retrievalProperties.keywordEnabled()) {
            long tk = System.currentTimeMillis();
            keywordCandidates = pgVectorChunkRepository.keywordSearch(
                    ownerId, documentIds, effectiveQuery,
                    embeddingProperties.model(), embeddingProperties.version(),
                    retrievalProperties.keywordCandidateK());
            log.info("retrieval.keyword.done elapsedMs={} candidates={}",
                    System.currentTimeMillis() - tk, keywordCandidates.size());
        }

        List<RetrievedChunkRow> merged = mergeAndRank(vectorCandidates, keywordCandidates);
        log.info("retrieval.search.done elapsedMs={} vectorCandidates={} keywordCandidates={} merged={} docs={}",
                System.currentTimeMillis() - ts, vectorCandidates.size(),
                keywordCandidates.size(), merged.size(), documentIds.size());

        double vectorWeight = retrievalProperties.normalisedVectorWeight();
        double keywordWeight = retrievalProperties.normalisedKeywordWeight();
        return merged.stream()
                .filter(row -> row.hybridScore(vectorWeight, keywordWeight) >= retrievalProperties.minScore())
                .limit(retrievalProperties.topK())
                .toList();
    }

    /**
     * Merges vector and keyword candidate lists by chunk id, computing a
     * combined hybrid score for each chunk. Chunks found by only one search
     * still receive a score (the missing component contributes zero).
     */
    private List<RetrievedChunkRow> mergeAndRank(
            List<RetrievedChunkRow> vectorCandidates,
            List<RetrievedChunkRow> keywordCandidates) {

        Map<UUID, RetrievedChunkRow> byId = new java.util.LinkedHashMap<>();

        for (RetrievedChunkRow row : vectorCandidates) {
            byId.merge(row.chunkId(), row, (existing, replacement) -> {
                // Prefer the vector row (has a real distance); keep keywordRank if present.
                double kr = replacement.keywordRank() != 0.0d ? replacement.keywordRank()
                        : existing.keywordRank();
                return new RetrievedChunkRow(
                        existing.chunkId(), existing.documentId(), existing.versionId(),
                        existing.chunkIndex(), existing.content(), existing.pageNumber(),
                        existing.section(), existing.tokenCount(), existing.contentHash(),
                        existing.filename(), existing.distance(), kr);
            });
        }

        for (RetrievedChunkRow row : keywordCandidates) {
            byId.merge(row.chunkId(), row, (existing, replacement) -> {
                // Prefer the existing row (has vector distance); take keywordRank from replacement.
                double kr = replacement.keywordRank();
                double dist = existing.distance() != 0.0d ? existing.distance() : 0.0d;
                return new RetrievedChunkRow(
                        existing.chunkId(), existing.documentId(), existing.versionId(),
                        existing.chunkIndex(), existing.content(), existing.pageNumber(),
                        existing.section(), existing.tokenCount(), existing.contentHash(),
                        existing.filename(), dist, kr);
            });
        }

        return byId.values().stream()
                .sorted((a, b) -> {
                    double va = a.hybridScore(
                            retrievalProperties.normalisedVectorWeight(),
                            retrievalProperties.normalisedKeywordWeight());
                    double vb = b.hybridScore(
                            retrievalProperties.normalisedVectorWeight(),
                            retrievalProperties.normalisedKeywordWeight());
                    return Double.compare(vb, va); // descending
                })
                .collect(Collectors.toList());
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