package com.veridoc.ai.conversation.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.veridoc.ai.conversation.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndOwnerId(UUID id, UUID ownerId);

    Page<Conversation> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Conversation ids owned by {@code ownerId} and attached to at least one
     * READY document. Feeds the retrieval authorization predicate.
     *
     * <p>Implemented in {@link ConversationDocumentRepository} against native
     * SQL: the join crosses two aggregates and needs an explicit ownership
     * predicate, which is clearer and cheaper as SQL than as JPQL.
     */
}