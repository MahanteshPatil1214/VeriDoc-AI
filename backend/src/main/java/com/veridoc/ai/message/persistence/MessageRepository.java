package com.veridoc.ai.message.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.veridoc.ai.message.domain.Message;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * Oldest-first page of a conversation's messages. Always called with a
     * conversation id that the service has already ownership-checked.
     */
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId, Pageable pageable);

    /** Most recent messages, newest-first, used to build the prompt window. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId
              and m.status = com.veridoc.ai.message.domain.MessageStatus.COMPLETE
            order by m.createdAt desc
            """)
    List<Message> findRecentComplete(@Param("conversationId") UUID conversationId,
                                     Pageable pageable);

    long countByConversationId(UUID conversationId);

    @Query("""
            select m from Message m
            where m.status = com.veridoc.ai.message.domain.MessageStatus.STREAMING
              and m.createdAt < :cutoff
            """)
    List<Message> findStaleStreaming(
            @Param("cutoff") java.time.Instant cutoff,
            Pageable pageable);
}