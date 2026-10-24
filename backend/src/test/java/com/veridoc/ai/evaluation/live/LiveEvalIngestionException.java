package com.veridoc.ai.evaluation.live;

/**
 * A safe, content-free ingestion failure.
 *
 * <p>Messages name the dataset document and the failed stage only. They never
 * contain document text, an extracted page, a provider response or a
 * credential.
 */
public final class LiveEvalIngestionException extends RuntimeException {

    public LiveEvalIngestionException(String message) {
        super(message);
    }

    public LiveEvalIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
