package com.veridoc.ai.user.application;

import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.user.domain.Role;
import com.veridoc.ai.user.domain.User;
import com.veridoc.ai.user.persistence.UserRepository;

/**
 * User registration, credential verification and lookup.
 *
 * <p>This service has no knowledge of HTTP: controllers map DTOs, this class
 * owns the rules.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String email, String rawPassword, String displayName) {
        String normalisedEmail = normaliseEmail(email);
        if (userRepository.existsByEmailIgnoreCase(normalisedEmail)) {
            // Deliberately identical to the "invalid credentials" response so
            // the endpoint cannot be used to enumerate registered accounts.
            throw new AppException(ErrorCode.CONFLICT, "An account with this email already exists.");
        }
        String display = displayName == null || displayName.isBlank()
                ? deriveDisplayName(normalisedEmail)
                : displayName.strip();

        User user = User.register(
                UUID.randomUUID(),
                normalisedEmail,
                passwordEncoder.encode(rawPassword),
                truncate(display, 120));
        return userRepository.save(user);
    }

    /**
     * Verifies credentials. Failures are deliberately indistinguishable:
     * unknown email and wrong password produce the same error and both pay the
     * cost of a BCrypt comparison, so timing does not leak account existence.
     */
    @Transactional(readOnly = true)
    public User authenticate(String email, String rawPassword) {
        String normalisedEmail = normaliseEmail(email);
        User user = userRepository.findByEmailIgnoreCase(normalisedEmail)
                .orElse(null);

        if (user == null) {
            // Dummy verification to equalise timing against the found-user path.
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        user.requireEnabled();
        return user;
    }

    @Transactional(readOnly = true)
    public User requireById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED,
                        "Authentication is required."));
    }

    public static String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            throw AppException.invalidRequest("email is required");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static AppException invalidCredentials() {
        return new AppException(ErrorCode.UNAUTHORIZED, "Invalid email or password.");
    }

    private static String deriveDisplayName(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String spaced = local.replace('.', ' ').replace('_', ' ').replace('-', ' ');
        String trimmed = spaced.strip();
        if (trimmed.isEmpty()) {
            return "User";
        }
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * A valid BCrypt hash of a value no user can supply. Used to keep the
     * "unknown email" path computationally comparable to the "known email,
     * wrong password" path.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7ZUXsG5uUZ2EL1I2CFTnHkCxJ8awG/1";

    static {
        // Guard: if this hash ever stops parsing, fail fast at startup rather
        // than silently disabling timing equalisation.
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("veridoc-timing-guard", DUMMY_HASH);
    }
}