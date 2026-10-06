package com.veridoc.ai.common.error;

import java.util.Map;

/**
 * Expected, recoverable application failure. The {@code message} is safe to
 * show to the user; the {@code cause} is logged but never serialised.
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, String> details;

    public AppException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public AppException(ErrorCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public AppException(ErrorCode code, String message, Map<String, String> details) {
        this(code, message, details, null);
    }

    public AppException(ErrorCode code,
                        String message,
                        Map<String, String> details,
                        Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }

    public int httpStatus() {
        return code.httpStatus();
    }

    // ------------------------------------------------------------------
    // Convenience factories for the most common cases.
    // ------------------------------------------------------------------

    public static AppException notFound(ErrorCode code, String what, Object id) {
        return new AppException(code, what + " not found: " + id);
    }

    public static AppException invalidRequest(String message) {
        return new AppException(ErrorCode.INVALID_REQUEST, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(ErrorCode.FORBIDDEN, message);
    }
}