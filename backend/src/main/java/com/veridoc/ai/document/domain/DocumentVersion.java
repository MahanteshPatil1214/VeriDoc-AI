package com.veridoc.ai.document.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An immutable ingest of a document's bytes. Chunk rows point at a version id,
 * which is what allows a future re-index to write a new vector set without
 * mixing incompatible embeddings into the same document.
 */
@Entity
@Table(name = "document_versions")
public class DocumentVersion {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "file_hash", nullable = false, updatable = false, length = 64)
    private String fileHash;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_version")
    private Integer embeddingVersion;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected DocumentVersion() {
    }

    public static DocumentVersion create(UUID id, UUID documentId, int version, String fileHash,
                                         String embeddingModel, Integer embeddingVersion) {
        DocumentVersion documentVersion = new DocumentVersion();
        documentVersion.id = id;
        documentVersion.documentId = documentId;
        documentVersion.version = version;
        documentVersion.fileHash = fileHash;
        documentVersion.embeddingModel = embeddingModel;
        documentVersion.embeddingVersion = embeddingVersion;
        return documentVersion;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getVersion() {
        return version;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Integer getEmbeddingVersion() {
        return embeddingVersion;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}