package com.veridoc.ai.security;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.common.trace.TraceContext;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

/**
 * Single entry point for obtaining the current caller. Business services use
 * this instead of accepting a user id parameter, which makes it impossible to
 * forget the ownership check.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthenticatedUser require() {
        AuthenticatedUser user = currentUser();
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
        }
        return user;
    }

    public static UUID requireId() {
        return require().userId();
    }

    public static AuthenticatedUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    /** Records the caller in the MDC for the remainder of the request. */
    public static void bindToMdc() {
        AuthenticatedUser user = currentUser();
        if (user != null) {
            org.slf4j.MDC.put(TraceContext.USER_ID, user.userId().toString());
        }
    }
}