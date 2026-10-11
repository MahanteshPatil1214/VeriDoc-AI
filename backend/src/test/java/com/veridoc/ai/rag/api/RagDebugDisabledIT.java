package com.veridoc.ai.rag.api;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;

import tools.jackson.databind.ObjectMapper;

/**
 * With {@code veridoc.rag.debug-enabled=false} the debugger must not expose
 * document text even to an admin: it reports the feature as absent.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class RagDebugDisabledIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void disableDebugger(DynamicPropertyRegistry registry) {
        registry.add("veridoc.rag.debug-enabled", () -> "false");
    }

    @Test
    @DisplayName("the debugger is a 404 even for an admin when disabled")
    void disabledDebuggerIsNotFoundForAdmin() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Debug Admin"))))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN' WHERE lower(email) = ?", email)).isEqualTo(1);
        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/admin/rag/debug")
                        .param("conversationId", UUID.randomUUID().toString())
                        .param("question", "anything")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    }
}