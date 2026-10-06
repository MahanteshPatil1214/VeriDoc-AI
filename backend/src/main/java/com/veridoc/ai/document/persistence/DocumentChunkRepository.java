package com.veridoc.ai.document.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.veridoc.ai.document.domain.DocumentChunk;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByVersionIdOrderByChunkIndexAsc(UUID versionId);

    long countByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    /**
     * Sanity check used by the ingestion pipeline: guarantees every vector in a
     * version belongs to the same embedding space before a document is marked
     * READY. Guards the "never mix incompatible embedding dimensions" rule.
     */
    @Query("""
            select count(distinct concat(c.embeddingModel, ':', c.embeddingVersion))
            from DocumentChunk c
            where c.versionId = :versionId
            """)
    long countDistinctEmbeddingFingerprints(@Param("versionId") UUID versionId);

    @Query("""
            select count(c) from DocumentChunk c
            where c.versionId = :versionId and c.embeddingModel = :model
              and c.embeddingVersion = :embeddingVersion
            """)
    long countMatchingEmbeddingFingerprint(@Param("versionId") UUID versionId,
                                          @Param("model") String model,
                                          @Param("embeddingVersion") Integer embeddingVersion);
}