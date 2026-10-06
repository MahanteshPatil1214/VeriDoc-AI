package com.veridoc.ai.message.domain;

/**
 * Persistence state of a message. A STREAMING message that never completed
 * (client disconnect, provider failure) is reconciled to FAILED or CANCELLED
 * by a sweeper job rather than being left dangling.
 */
public enum MessageStatus {
    COMPLETE,
    STREAMING,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED || this == CANCELLED;
    }
}