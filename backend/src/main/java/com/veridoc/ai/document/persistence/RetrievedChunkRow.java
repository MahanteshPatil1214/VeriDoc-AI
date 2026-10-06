package com.veridoc.ai.document.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Projection of a chunk plus its similarity score, returned by the authorized
 * vector search. Not a JPA entity: it is produced by native SQL.
 */
public record RetrievedChunkRow(

        @Column(name = "id") UUID chunkId,

        @Column(name = "document_id") UUID documentId,

        @Column(name = "version_id") UUID versionId,

        @Column(name = "chunk_index") int chunkIndex,

        @Column(name = "content") String content,

        @Column(name = "page_number") Integer pageNumber,

        @Column(name = "section") String section,

        @Column(name = "token_count") int tokenCount,

        @Column(name = "content_hash") String contentHash,

        @Column(name = "filename") String filename,

        @Column(name = "distance") double distance
) {
    /** Cosine distance in [0,2]; converted to a similarity in [-1,1]. */
    public double similarity() {
        return 1.0d - distance;
    }
}