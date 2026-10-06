package com.veridoc.ai.document.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A retrievable unit of evidence.
 *
 * <p>The pgvector column is {@code vector(768)}; the mapping is handled by
 * {@code VectorType} so the entity stays a plain Java type.
 *
 * <p>{@code ownerId} is denormalised here on purpose: it lets authorized vector
 * search filter on {@code owner_id} in the same SQL statement as the similarity
 * computation, which is what makes cross-tenant leakage structurally impossible
 * rather than merely filtered out afterwards.
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "page_number", updatable = false)
    private Integer pageNumber;

    @Column(name = "section", updatable = false, length = 512)
    private String section;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_version")
    private Integer embeddingVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected DocumentChunk() {
    }

    public static DocumentChunk create(UUID id,
                                       UUID documentId,
                                       UUID versionId,
                                       UUID ownerId,
                                       int chunkIndex,
                                       String content,
                                       Integer pageNumber,
                                       String section,
                                       int tokenCount,
                                       String contentHash,
                                       String embeddingModel,
                                       Integer embeddingVersion) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.id = id;
        chunk.documentId = documentId;
        chunk.versionId = versionId;
        chunk.ownerId = ownerId;
        chunk.chunkIndex = chunkIndex;
        chunk.content = content;
        chunk.pageNumber = pageNumber;
        chunk.section = section;
        chunk.tokenCount = tokenCount;
        chunk.contentHash = contentHash;
        chunk.embeddingModel = embeddingModel;
        chunk.embeddingVersion = embeddingVersion;
        return chunk;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getSection() {
        return section;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Integer getEmbeddingVersion() {
        return embeddingVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}