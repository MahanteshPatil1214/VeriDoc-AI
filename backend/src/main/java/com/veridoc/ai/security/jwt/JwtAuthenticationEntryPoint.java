package com.veridoc.ai.security.jwt;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.veridoc.ai.common.trace.TraceContext;
import com.veridoc.ai.security.CurrentUserAccessor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders the standard error envelope for unauthenticated requests instead of
 * the framework's HTML/empty body, and binds the caller to the MDC so every log
 * line emitted afterwards carries the user id.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        CurrentUserAccessor.bindToMdc();
        String traceId = TraceContext.currentTraceId();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);

        String message = "Authentication is required.";
        StringBuilder json = new StringBuilder(256);
        json.append("{\"code\":\"UNAUTHORIZED\",\"message\":\"")
                .append(escape(message))
                .append("\",\"details\":{},\"timestamp\":\"")
                .append(Instant.now())
                .append("\",\"traceId\":\"").append(escape(traceId)).append("\"}");

        response.getWriter().write(json.toString());
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Utility for tests asserting the envelope shape. */
    public static Map<String, Object> template() {
        return Map.of(
                "code", "UNAUTHORIZED",
                "message", "Authentication is required.",
                "details", Map.of(),
                "traceId", "unknown");
    }
}