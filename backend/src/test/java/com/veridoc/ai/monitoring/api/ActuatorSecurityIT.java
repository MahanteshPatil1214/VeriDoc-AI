package com.veridoc.ai.monitoring.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;

import tools.jackson.databind.ObjectMapper;

/**
 * Actuator exposure follows the security configuration: health and info are
 * deliberately public; operational endpoints (metrics, prometheus, flyway, ...)
 * are ADMIN-only so operational data never leaks to anonymous callers or
 * regular users.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class ActuatorSecurityIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("health and info are public")
    void healthAndInfoArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("metrics, prometheus and flyway require authentication")
    void operationalEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/flyway"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a regular user is denied operational endpoints")
    void regularUserCannotReadOperationalEndpoints() throws Exception {
        String token = tokenForNewUser();

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/flyway")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can read metrics, prometheus and flyway")
    void adminCanReadOperationalEndpoints() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        register(email);
        promoteToAdmin(email);
        String token = login(email).accessToken();

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/flyway")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void promoteToAdmin(String email) {
        int updated = jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN' WHERE lower(email) = ?", email);
        org.assertj.core.api.Assertions.assertThat(updated).isEqualTo(1);
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Actuator Security"))))
                .andExpect(status().isCreated());
    }

    private AuthResponse login(String email) throws Exception {
        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new AuthResponse(objectMapper.readTree(login).get("accessToken").asText());
    }

    private record AuthResponse(String accessToken) {
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        register(email);
        return login(email).accessToken();
    }
}