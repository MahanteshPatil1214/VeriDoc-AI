package com.veridoc.ai.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.veridoc.ai.user.api.dto.UserResponse;

import io.swagger.v3.oas.annotations.media.Schema;

public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(name = "RegisterRequest")
    public record RegisterRequest(

            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid address")
            @Size(max = 320, message = "email must not exceed 320 characters")
            String email,

            @NotBlank(message = "password is required")
            @Size(min = 12, max = 128,
                    message = "password must be between 12 and 128 characters")
            String password,

            @NotBlank(message = "displayName is required")
            @Size(max = 120, message = "displayName must not exceed 120 characters")
            String displayName
    ) {
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(

            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid address")
            @Size(max = 320)
            String email,

            @NotBlank(message = "password is required")
            @Size(max = 128)
            String password
    ) {
    }

    @Schema(name = "RefreshRequest")
    public record RefreshRequest(
            @NotBlank(message = "refreshToken is required")
            String refreshToken
    ) {
    }

    @Schema(name = "AuthResponse")
    public record AuthResponse(
            String tokenType,
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserResponse user
    ) {
    }
}