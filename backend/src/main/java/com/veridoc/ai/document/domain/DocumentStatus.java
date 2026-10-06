package com.veridoc.ai.document.domain;

/**
 * Lifecycle of an uploaded document. The frontend renders this directly, so the
 * transitions are deliberately explicit and observable.
 */
public enum DocumentStatus {

    /** Bytes accepted, not yet written to storage. */
    UPLOADING,
    /** Handed to the asynchronous pipeline. */
    PROCESSING,
    /** PDF text extraction in progress. */
    EXTRACTING,
    /** Chunk construction in progress. */
    CHUNKING,
    /** Embedding generation in progress. */
    EMBEDDING,
    /** Persisting vectors in pgvector. */
    INDEXING,
    /** Retrievable. */
    READY,
    /** Terminal failure; retryable via POST /documents/{id}/retry. */
    FAILED,
    /** Soft-deleted; excluded from all retrieval. */
    DELETED;

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == DELETED;
    }

    public boolean isRetrievable() {
        return this == READY;
    }

    public boolean isProcessing() {
        return this == UPLOADING || this == PROCESSING || this == EXTRACTING
                || this == CHUNKING || this == EMBEDDING || this == INDEXING;
    }

    public boolean isFailure() {
        return this == FAILED;
    }

    /** Progress ordering used by the frontend stepper; DELETED is not shown. */
    public int progressStep() {
        return switch (this) {
            case UPLOADING -> 0;
            case PROCESSING -> 1;
            case EXTRACTING -> 2;
            case CHUNKING -> 3;
            case EMBEDDING -> 4;
            case INDEXING -> 5;
            case READY -> 6;
            case FAILED -> -1;
            case DELETED -> -1;
        };
    }

    /** Allowed forward transitions, enforced by the pipeline. */
    public boolean canTransitionTo(DocumentStatus next) {
        if (this == DELETED) {
            return false;
        }
        if (next == DELETED) {
            return true;
        }
        if (this == FAILED) {
            // Retry re-enters the pipeline at PROCESSING.
            return next == PROCESSING || next == UPLOADING;
        }
        if (this == READY) {
            // Re-processing after a document version change.
            return next == PROCESSING;
        }
        return next.progressStep() > this.progressStep();
    }
}