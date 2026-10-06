package com.veridoc.ai.common.error;

/**
 * Canonical, stable error codes exposed to clients. Never rename a code once
 * published: clients switch on it.
 */
public enum ErrorCode {

    INVALID_REQUEST(400),
    VALIDATION_FAILED(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    DOCUMENT_NOT_FOUND(404),
    CONVERSATION_NOT_FOUND(404),
    MESSAGE_NOT_FOUND(404),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    UNSUPPORTED_FILE(415),
    DOCUMENT_TOO_LARGE(413),
    INVALID_PDF(422),
    EMPTY_DOCUMENT(422),
    ENCRYPTED_PDF(422),
    DOCUMENT_PROCESSING_FAILED(500),
    EMBEDDING_FAILED(502),
    RETRIEVAL_FAILED(502),
    LLM_TIMEOUT(504),
    LLM_RATE_LIMITED(429),
    LLM_UNAVAILABLE(502),
    UNAUTHORIZED_DOCUMENT(403),
    DUPLICATE_DOCUMENT(409),
    DOCUMENT_NOT_READY(409),
    RATE_LIMIT_EXCEEDED(429),
    CONFLICT(409),
    STREAM_INTERRUPTED(500),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}