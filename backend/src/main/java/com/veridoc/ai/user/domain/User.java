package com.veridoc.ai.user.domain;

import java.time.Instant;
import java.util.UUID;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A registered user. Entities are never returned directly from the API; use
 * {@code UserResponse}.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /** BCrypt hash. Never serialised, never logged. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role = Role.USER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected User() {
    }

    public static User register(UUID id, String email, String passwordHash, String displayName) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.role = Role.USER;
        user.enabled = true;
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void disable() {
        this.enabled = false;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new AppException(ErrorCode.FORBIDDEN, "This account has been disabled.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof User other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}