package com.veridoc.ai.message.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 16)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MessageStatus status;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
    }

    public static Message user(UUID id, UUID conversationId, String content) {
        return create(id, conversationId, MessageRole.USER, content, MessageStatus.COMPLETE, null);
    }

    public static Message assistantStreaming(UUID id, UUID conversationId, String model) {
        return create(id, conversationId, MessageRole.ASSISTANT, "", MessageStatus.STREAMING, model);
    }

    private static Message create(UUID id, UUID conversationId, MessageRole role, String content,
                                  MessageStatus status, String model) {
        Message message = new Message();
        message.id = id;
        message.conversationId = conversationId;
        message.role = role;
        message.content = content == null ? "" : content;
        message.status = status;
        message.model = model;
        return message;
    }

    public void complete(String finalContent, Integer promptTokens, Integer completionTokens,
                         Long latencyMs) {
        this.content = finalContent == null ? "" : finalContent;
        this.status = MessageStatus.COMPLETE;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens);
        this.latencyMs = latencyMs;
        this.errorCode = null;
    }

    public void fail(String errorCode) {
        this.status = MessageStatus.FAILED;
        this.errorCode = errorCode;
    }

    public void cancel() {
        this.status = MessageStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}