package com.veridoc.ai.conversation.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.veridoc.ai.conversation.domain.ConversationDocument;

public interface ConversationDocumentRepository
        extends JpaRepository<ConversationDocument, ConversationDocument.Key> {

    List<ConversationDocument> findByKeyConversationId(UUID conversationId);

    @Query("""
            select cd.key.documentId
            from ConversationDocument cd
            where cd.key.conversationId = :conversationId
            """)
    List<UUID> findDocumentIds(@Param("conversationId") UUID conversationId);

    @Modifying
    @Query("""
            delete from ConversationDocument cd
            where cd.key.conversationId = :conversationId
            """)
    int deleteByConversationId(@Param("conversationId") UUID conversationId);

    @Modifying
    @Query("""
            delete from ConversationDocument cd
            where cd.key.documentId = :documentId
            """)
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Conversation ids, among {@code conversationIds}, that are attached to at
     * least one READY document owned by {@code ownerId}.
     *
     * <p>Native SQL on purpose. The retrieval layer needs this as a set
     * membership check inside its authorization predicate, and a
     * {@code conversation_id = ANY(:ids)} filter is both clearer and faster as
     * SQL than as JPQL. The ownership predicate is repeated here rather than
     * joined from {@code conversations} so that this query cannot be widened
     * into an information leak by a future caller that forgets the owner.
     */
    @Query(value = """
            SELECT cd.conversation_id
            FROM conversation_documents cd
            JOIN documents d ON d.id = cd.document_id
            WHERE cd.conversation_id IN (:conversationIds)
              AND d.owner_id = :ownerId
              AND d.status = 'READY'
            """, nativeQuery = true)
    List<UUID> findConversationIdsWithReadyDocuments(
            @Param("ownerId") UUID ownerId,
            @Param("conversationIds") List<UUID> conversationIds);
}