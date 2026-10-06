package com.veridoc.ai.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.veridoc.ai.common.trace.TraceContext;

/**
 * Translates every exception into the {@link ApiErrorResponse} envelope.
 * Nothing that could leak internals (stack traces, SQL, driver messages) is
 * ever returned to the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleApp(AppException ex, HttpServletRequest request) {
        log.warn("Handled application error code={} path={} message={}",
                ex.code(), request.getRequestURI(), ex.getMessage());
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> details.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> details.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));

        log.debug("Validation failed path={} fields={}", request.getRequestURI(), details.keySet());
        return build(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.", details);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception ex,
                                                                   HttpServletRequest request) {
        log.debug("Malformed request path={} type={}", request.getRequestURI(), ex.getClass());
        return build(ErrorCode.INVALID_REQUEST, "The request could not be parsed or is malformed.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.DOCUMENT_TOO_LARGE,
                "The uploaded document exceeds the maximum allowed size.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        // Deliberately opaque: do not disclose whether the resource exists.
        return build(ErrorCode.FORBIDDEN, "You do not have access to this resource.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(ErrorCode.UNAUTHORIZED, "Authentication is required.");
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, NoHandlerFoundException.class,
            NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFoundRoute(Exception ex) {
        return build(ErrorCode.NOT_FOUND, "The requested resource does not exist.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = TraceContext.currentTraceId();
        log.error("Unhandled exception path={} traceId={}", request.getRequestURI(), traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred. Please try again.",
                        Map.of(),
                        java.time.Instant.now(),
                        traceId));
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code, String message) {
        return build(code, message, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code,
                                                   String message,
                                                   Map<String, String> details) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiErrorResponse.of(code, message, details, TraceContext.currentTraceId()));
    }
}