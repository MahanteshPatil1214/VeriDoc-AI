package com.veridoc.ai.common.error;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single error envelope returned by every failing API call.
 * Internal stack traces and exception messages are never serialised.
 */
@Schema(name = "ApiError", description = "Structured application error")
public record ApiErrorResponse(

        @Schema(example = "DOCUMENT_TOO_LARGE") ErrorCode code,

        @Schema(example = "The uploaded document exceeds the maximum allowed size.")
        String message,

        @Schema(description = "Field-level details, present for validation failures")
        Map<String, String> details,

        @Schema(example = "2026-10-05T09:15:30Z") Instant timestamp,

        @Schema(description = "Correlation id; matches the X-Trace-Id response header")
        String traceId
) {
    public static ApiErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ApiErrorResponse(code, message, Map.of(), Instant.now(), traceId);
    }

    public static ApiErrorResponse of(ErrorCode code,
                                      String message,
                                      Map<String, String> details,
                                      String traceId) {
        return new ApiErrorResponse(code, message, details == null ? Map.of() : Map.copyOf(details),
                Instant.now(), traceId);
    }
}