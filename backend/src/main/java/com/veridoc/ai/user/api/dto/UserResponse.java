package com.veridoc.ai.user.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.veridoc.ai.user.domain.Role;
import com.veridoc.ai.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Public projection of a user. Never contains the password hash.
 */
@Schema(name = "UserResponse")
public record UserResponse(
        @Schema(example = "3f6b...") UUID id,

        @Schema(example = "ada@example.com") String email,

        @Schema(example = "Ada Lovelace") String displayName,

        Role role,

        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.getCreatedAt());
    }
}