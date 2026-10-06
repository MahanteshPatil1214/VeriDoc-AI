package com.veridoc.ai.feedback.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", nullable = false, updatable = false, length = 16)
    private FeedbackRating rating;

    @Column(name = "comment", length = 2000)
    private String comment;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Feedback() {
    }

    public static Feedback create(UUID id, UUID messageId, UUID userId,
                                  FeedbackRating rating, String comment) {
        Feedback feedback = new Feedback();
        feedback.id = id;
        feedback.messageId = messageId;
        feedback.userId = userId;
        feedback.rating = rating;
        feedback.comment = comment == null || comment.isBlank() ? null : comment.strip();
        return feedback;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getUserId() {
        return userId;
    }

    public FeedbackRating getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}