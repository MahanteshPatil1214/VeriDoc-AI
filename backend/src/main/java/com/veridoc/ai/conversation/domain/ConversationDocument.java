package com.veridoc.ai.conversation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Join entity binding a conversation to a document the user selected.
 * Retrieval searches only documents reachable through this table.
 */
@Entity
@Table(name = "conversation_documents")
public class ConversationDocument {

    @EmbeddedId
    private Key key;

    @Column(name = "added_at", nullable = false, insertable = false, updatable = false)
    private Instant addedAt;

    protected ConversationDocument() {
    }

    public static ConversationDocument attach(UUID conversationId, UUID documentId) {
        ConversationDocument link = new ConversationDocument();
        link.key = new Key(conversationId, documentId);
        return link;
    }

    public UUID getConversationId() {
        return key.conversationId();
    }

    public UUID getDocumentId() {
        return key.documentId();
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    /** Composite primary key. */
    public record Key(UUID conversationId, UUID documentId) {
    }
}