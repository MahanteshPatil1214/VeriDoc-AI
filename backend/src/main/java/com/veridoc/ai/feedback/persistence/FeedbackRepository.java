package com.veridoc.ai.feedback.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.veridoc.ai.feedback.domain.Feedback;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    Optional<Feedback> findByMessageIdAndUserId(UUID messageId, UUID userId);

    /** One query to decorate a whole thread with the caller's own ratings. */
    List<Feedback> findByUserIdAndMessageIdIn(UUID userId, Collection<UUID> messageIds);
}
