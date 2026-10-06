package com.veridoc.ai.common.trace;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Records HTTP request latency and outcome as Micrometer metrics. */
@Component
public class HttpMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpMetricsFilter.class);

    private final MeterRegistry registry;

    public HttpMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        String template = routeTemplate(request);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.nanoTime() - startNs;
            String status = String.valueOf(response.getStatus());
            String method = request.getMethod();

            Timer.builder("veridoc.http.server.requests")
                    .description("HTTP request latency")
                    .tag("method", method)
                    .tag("uri", template)
                    .tag("status", status)
                    .tag("outcome", outcome(response.getStatus()))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(elapsed, TimeUnit.NANOSECONDS);

            if (response.getStatus() >= 400) {
                log.debug("HTTP {} {} -> {} in {}ms",
                        method, template, status, TimeUnit.NANOSECONDS.toMillis(elapsed));
            }
        }
    }

    private String outcome(int status) {
        if (status < 400) {
            return "SUCCESS";
        }
        if (status < 500) {
            return "CLIENT_ERROR";
        }
        return "SERVER_ERROR";
    }

    /**
     * Low-cardinality route template. Falls back to a single "unmatched" bucket
     * so that path variables (which include UUIDs) never explode cardinality.
     */
    private String routeTemplate(HttpServletRequest request) {
        String path = request.getRequestURI();
        String base = path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
        if (base.length() > 80) {
            return "unmatched";
        }
        String normalised = base.replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "/{id}");
        return normalised.replaceAll("/\\d+", "/{id}");
    }
}