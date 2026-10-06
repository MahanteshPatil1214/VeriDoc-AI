package com.veridoc.ai.common.trace;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Establishes a correlation id for every request, echoes it back on the
 * response, and puts it in the MDC so every log line and error envelope is
 * traceable. Also records the authenticated user id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = sanitise(request.getHeader(TraceContext.TRACE_ID_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String requestId = sanitise(request.getHeader(TraceContext.REQUEST_ID_HEADER));

        MDC.put(TraceContext.TRACE_ID, traceId);
        if (requestId != null) {
            MDC.put(TraceContext.REQUEST_ID, requestId);
        }
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);

        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                Object principal = authentication.getPrincipal();
                if (principal instanceof com.veridoc.ai.security.authenticated.AuthenticatedUser user) {
                    MDC.put(TraceContext.USER_ID, user.userId().toString());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.TRACE_ID);
            MDC.remove(TraceContext.REQUEST_ID);
            MDC.remove(TraceContext.USER_ID);
        }
    }

    /** Never trust an inbound correlation id verbatim. */
    private String sanitise(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}