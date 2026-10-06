package com.veridoc.ai.security.jwt;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the bearer token, validates it, and populates the security context.
 *
 * <p>A missing or empty token is left unauthenticated. An invalid or expired
 * token is never allowed to escape as an exception: exceptions thrown from a
 * filter bypass {@code @RestControllerAdvice} entirely, so an
 * {@link AppException} rethrown here would surface as a container 500 page
 * instead of the JSON 401 the API contract promises. The filter instead clears
 * the context and lets the request continue anonymously; protected endpoints
 * are then rejected by {@link JwtAuthenticationEntryPoint}, which owns the
 * single implementation of the error body.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenService tokenService;

    public JwtAuthenticationFilter(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length()).strip();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = tokenService.parseAccessToken(token);
            var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (AppException e) {
            SecurityContextHolder.clearContext();
            // Debug, not warn: an invalid token is a routine client mistake, and
            // logging it at warn on every attempt is a log-flooding amplifier.
            log.debug("Rejected bearer token on {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}