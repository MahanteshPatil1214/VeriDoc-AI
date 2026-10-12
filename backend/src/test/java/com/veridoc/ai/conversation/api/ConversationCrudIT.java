package com.veridoc.ai.conversation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Conversation rename + delete: validation, ownership isolation and the
 * cascade that removes messages with the conversation.
 */
@AutoConfigureMockMvc
class ConversationCrudIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("renaming a conversation updates its title")
    void renameUpdatesTitle() throws Exception {
        String token = tokenForNewUser();
        UUID id = createConversation(token, "New chat");

        mockMvc.perform(patch("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"Q3 financials\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Q3 financials"));

        mockMvc.perform(get("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Q3 financials"));
    }

    @Test
    @DisplayName("renaming rejects a blank title")
    void renameRejectsBlankTitle() throws Exception {
        String token = tokenForNewUser();
        UUID id = createConversation(token, "New chat");

        mockMvc.perform(patch("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("another user cannot rename or delete the conversation")
    void mutationsAreOwnerScoped() throws Exception {
        String owner = tokenForNewUser();
        UUID id = createConversation(owner, "Private");
        String other = tokenForNewUser();

        mockMvc.perform(patch("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + other)
                        .contentType("application/json")
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));

        mockMvc.perform(delete("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("deleting a conversation removes it from detail and the listing")
    void deleteRemovesConversation() throws Exception {
        String token = tokenForNewUser();
        UUID id = createConversation(token, "Disposable");

        mockMvc.perform(delete("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));

        String listBody = mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("Disposable");
    }

    private UUID createConversation(String token, String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Crud Test"))))
                .andExpect(status().isCreated());

        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }
}
