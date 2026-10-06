package com.veridoc.ai.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest;
import com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Authentication against a real PostgreSQL schema created by Flyway. No Gemini
 * call is made, so no API key is needed.
 *
 * <p>Jackson 3 is used here, not Jackson 2: Spring Boot 4 auto-configures
 * {@code tools.jackson} only, so a Jackson 2 {@code ObjectMapper} is not a bean.
 */
@AutoConfigureMockMvc
class AuthControllerIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the migration installs pgvector")
    void pgVectorIsInstalled() {
        assertPgVectorInstalled(jdbcTemplate);
    }

    @Test
    @DisplayName("register returns a token pair and never echoes the password")
    void registerSucceeds() throws Exception {
        String email = uniqueEmail();

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, "Ada Lovelace"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("login yields an access token that authenticates /me")
    void loginSucceeds() throws Exception {
        String email = uniqueEmail();
        register(email);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(response).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("a refresh token mints a new access token")
    void refreshSucceeds() throws Exception {
        String email = uniqueEmail();
        register(email);
        String refreshToken = loginAndGet(email, "refreshToken");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("an access token cannot be used where a refresh token is required")
    void accessTokenIsNotARefreshToken() throws Exception {
        String email = uniqueEmail();
        register(email);
        String accessToken = loginAndGet(email, "accessToken");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RefreshRequest(accessToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()));
    }

    @Test
    @DisplayName("wrong password and unknown email both yield UNAUTHORIZED with the same message")
    void loginRejectsBadCredentials() throws Exception {
        String email = uniqueEmail();
        register(email);

        String wrongPasswordBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "wrong password entirely"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()))
                .andReturn().getResponse().getContentAsString();

        String unknownEmailBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(uniqueEmail(), PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()))
                .andReturn().getResponse().getContentAsString();

        assertThat(messageOf(wrongPasswordBody)).isEqualTo(messageOf(unknownEmailBody));
    }

    @Test
    @DisplayName("duplicate registration is rejected case-insensitively")
    void duplicateRegistrationRejected() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email.toUpperCase(), "another password",
                                        "Someone"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.name()));
    }

    @Test
    @DisplayName("weak passwords fail validation before any persistence")
    void weakPasswordRejected() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "short", "Weak"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.details.password").exists());

        assertThat(countUsers(email)).isZero();
    }

    @Test
    @DisplayName("protected endpoints reject missing, malformed and tampered tokens")
    void protectedEndpointsRequireValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());

        String email = uniqueEmail();
        register(email);
        String token = loginAndGet(email, "accessToken");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("two users are isolated: a token cannot read another user's identity")
    void usersAreIsolated() throws Exception {
        String firstEmail = uniqueEmail();
        String secondEmail = uniqueEmail();
        register(firstEmail);
        register(secondEmail);

        String secondToken = loginAndGet(secondEmail, "accessToken");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(secondEmail));
    }

    @Test
    @DisplayName("every response carries the correlation id header and echoes the inbound one")
    void correlationIdIsPropagated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Trace-Id", "abc123"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Trace-Id", "abc123"));
    }

    private String messageOf(String json) throws Exception {
        return objectMapper.readTree(json).get("message").asText();
    }

    private long countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, "Test User"))))
                .andExpect(status().isCreated());
    }

    private String loginAndGet(String email, String field) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get(field).asText();
    }
}