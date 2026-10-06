package com.veridoc.ai.citation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A verified pointer from an assistant message back to the exact chunk that
 * supports it.
 *
 * <p>Every field is populated by the retrieval layer from a real
 * {@code document_chunks} row. The LLM is never permitted to author citation
 * metadata, and this entity deliberately has no foreign key to
 * {@code document_chunks} so the evidence trail survives document deletion.
 */
@Entity
@Table(name = "citations")
public class Citation {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    /** 1-based position, matching the {@code [n]} marker in the answer text. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_filename", nullable = false, updatable = false, length = 512)
    private String documentFilename;

    @Column(name = "chunk_id", nullable = false, updatable = false)
    private UUID chunkId;

    @Column(name = "page_number", updatable = false)
    private Integer pageNumber;

    @Column(name = "section", updatable = false, length = 512)
    private String section;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "quoted_text", columnDefinition = "text")
    private String quotedText;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Citation() {
    }

    public static Citation create(UUID id,
                                  UUID messageId,
                                  int ordinal,
                                  UUID documentId,
                                  String documentFilename,
                                  UUID chunkId,
                                  Integer pageNumber,
                                  String section,
                                  Double relevanceScore,
                                  String quotedText) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("Citation ordinal must be 1-based");
        }
        Citation citation = new Citation();
        citation.id = id;
        citation.messageId = messageId;
        citation.ordinal = ordinal;
        citation.documentId = documentId;
        citation.documentFilename = documentFilename;
        citation.chunkId = chunkId;
        citation.pageNumber = pageNumber;
        citation.section = section;
        citation.relevanceScore = relevanceScore;
        citation.quotedText = quotedText;
        return citation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getDocumentFilename() {
        return documentFilename;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getSection() {
        return section;
    }

    public Double getRelevanceScore() {
        return relevanceScore;
    }

    public String getQuotedText() {
        return quotedText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}