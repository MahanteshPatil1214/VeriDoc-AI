package com.veridoc.ai.conversation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "title", nullable = false, length = 255)
    private String title = "New chat";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    protected Conversation() {
    }

    public static Conversation create(UUID id, UUID ownerId, String title) {
        Conversation conversation = new Conversation();
        conversation.id = id;
        conversation.ownerId = ownerId;
        conversation.title = (title == null || title.isBlank()) ? "New chat" : title.trim();
        return conversation;
    }

    public void rename(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            return;
        }
        String trimmed = newTitle.strip();
        this.title = trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    /** Derives a title from the first user question, keeping the UI tidy. */
    public void autoTitleFrom(String question) {
        if (this.title.equals("New chat") && question != null && !question.isBlank()) {
            String flat = question.replaceAll("\\s+", " ").strip();
            this.title = flat.length() <= 80 ? flat : flat.substring(0, 77) + "...";
        }
    }

    public void touch() {
    }

    public boolean isOwnedBy(UUID candidate) {
        return ownerId != null && ownerId.equals(candidate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}