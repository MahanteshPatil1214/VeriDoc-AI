package com.veridoc.ai.feedback.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Answer feedback against a real schema: the unique (message, user) constraint,
 * ownership isolation, and the rating surfacing on the thread feed.
 */
@AutoConfigureMockMvc
class FeedbackIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a thumb is stored and returned with the message thread")
    void submitStoresRating() throws Exception {
        Session session = newSession();
        UUID conversationId = createConversation(session.token());
        UUID messageId = assistantMessage(conversationId);

        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.rating").value("UP"))
                .andExpect(jsonPath("$.comment").doesNotExist());

        mockMvc.perform(get("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(session.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feedback").value("UP"));
    }

    @Test
    @DisplayName("re-rating updates in place and deleting clears the thumb")
    void reRatingAndClearAreIdempotent() throws Exception {
        Session session = newSession();
        UUID conversationId = createConversation(session.token());
        UUID messageId = assistantMessage(conversationId);

        rate(session.token(), conversationId, messageId, "UP", null);
        rate(session.token(), conversationId, messageId, "DOWN", "Missed the deadline on page 2.");

        assertThat(feedbackRows(messageId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(session.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feedback").value("DOWN"))
                .andExpect(jsonPath("$[0].feedbackComment")
                        .value("Missed the deadline on page 2."));

        mockMvc.perform(delete(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(session.token())))
                .andExpect(status().isNoContent());

        assertThat(feedbackRows(messageId)).isZero();
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(session.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feedback").doesNotExist());
    }

    @Test
    @DisplayName("another user cannot rate or clear a foreign answer")
    void feedbackIsOwnerScoped() throws Exception {
        Session owner = newSession();
        UUID conversationId = createConversation(owner.token());
        UUID messageId = assistantMessage(conversationId);
        Session other = newSession();

        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(other.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"UP\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));

        assertThat(feedbackRows(messageId)).isZero();
    }

    @Test
    @DisplayName("user messages and unfinished answers cannot be rated")
    void onlyCompletedAssistantAnswersAreRatable() throws Exception {
        Session session = newSession();
        UUID conversationId = createConversation(session.token());
        UUID userMessageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages (id, conversation_id, role, content, status)
                VALUES (?, ?, 'USER', 'What is the notice period?', 'COMPLETE')
                """, userMessageId, conversationId);

        mockMvc.perform(put(feedbackPath(conversationId, userMessageId))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"UP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));

        UUID streamingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages (id, conversation_id, role, content, status)
                VALUES (?, ?, 'ASSISTANT', 'partial', 'STREAMING')
                """, streamingId, conversationId);

        mockMvc.perform(put(feedbackPath(conversationId, streamingId))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"DOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("unknown messages, malformed ratings and anonymous calls are rejected")
    void rejectsInvalidRequests() throws Exception {
        Session session = newSession();
        UUID conversationId = createConversation(session.token());

        mockMvc.perform(put(feedbackPath(conversationId, UUID.randomUUID()))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"UP\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.MESSAGE_NOT_FOUND.name()));

        UUID messageId = assistantMessage(conversationId);
        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content("{\"rating\":\"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));

        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .contentType("application/json")
                        .content("{\"rating\":\"UP\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a note longer than 2000 characters fails validation")
    void rejectsOverlongComment() throws Exception {
        Session session = newSession();
        UUID conversationId = createConversation(session.token());
        UUID messageId = assistantMessage(conversationId);

        String comment = "x".repeat(2001);
        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(session.token()))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("rating", "DOWN", "comment", comment))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.details.comment").exists());
    }

    // ---- helpers -------------------------------------------------------

    private record Session(String token) {
    }

    private Session newSession() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, "Feedback Test"))))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return new Session(objectMapper.readTree(body).get("accessToken").asText());
    }

    private UUID createConversation(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"title\":\"Feedback\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID assistantMessage(UUID conversationId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages (id, conversation_id, role, content, status)
                VALUES (?, ?, 'ASSISTANT', 'The notice period is 30 days.', 'COMPLETE')
                """, id, conversationId);
        return id;
    }

    private void rate(String token, UUID conversationId, UUID messageId, String rating,
                      String comment) throws Exception {
        String body = comment == null
                ? "{\"rating\":\"" + rating + "\"}"
                : objectMapper.writeValueAsString(
                        java.util.Map.of("rating", rating, "comment", comment));
        mockMvc.perform(put(feedbackPath(conversationId, messageId))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(rating));
    }

    private long feedbackRows(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM feedback WHERE message_id = ?", Long.class, messageId);
    }

    private static String feedbackPath(UUID conversationId, UUID messageId) {
        return "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/feedback";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
