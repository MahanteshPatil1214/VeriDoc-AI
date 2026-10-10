package com.veridoc.ai.conversation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.rag.ChatService;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;
import com.veridoc.ai.user.domain.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Streaming (SSE) grounding chat. Verifies the incremental event contract
 * ({@code token*} -> {@code citations} -> {@code done}), persistence of the
 * streamed answer, the no-evidence short-circuit, ownership enforcement, and
 * that a client disconnect reconciles the assistant row to {@code CANCELLED}.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class ChatStreamingIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String GROUNDED_QUERY =
            "VeriDoc AI verifies claims against uploaded source documents";
    private static final String REFUSAL =
            "I couldn't find enough information in the uploaded documents to answer that.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatService chatService;

    @Test
    @DisplayName("streaming a grounded question emits tokens, citations, then done")
    void groundedStreamEmitsEventsAndPersists() throws Exception {
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "contract.pdf");
        UUID conversationId = createConversation(token, "Streaming contract");
        attach(token, conversationId, docId);

        String body = streamBody(token, conversationId, GROUNDED_QUERY);

        assertThat(body).contains("event:token");
        assertThat(body).contains("event:citations");
        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("event:error");
        assertThat(body).contains("Based on the uploaded documents");
        assertThat(body).contains("contract.pdf");
        assertThat(body).contains("VeriDoc AI verifies claims");

        String messages = messages(token, conversationId);
        JsonNode json = objectMapper.readTree(messages);
        assertThat(json.size()).isEqualTo(2);
        JsonNode assistant = json.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("ASSISTANT");
        assertThat(assistant.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(assistant.get("content").asText()).contains("Based on the uploaded documents");
    }

    @Test
    @DisplayName("an unsupported question streams the refusal and no citations")
    void unsupportedQuestionStreamsRefusal() throws Exception {
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "manual.pdf");
        UUID conversationId = createConversation(token, "Streaming manual");
        attach(token, conversationId, docId);

        String body = streamBody(token, conversationId, "Why do zebras cross rivers safely?");

        assertThat(body).contains("event:token");
        assertThat(body).contains(REFUSAL);
        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("event:citations");

        JsonNode json = objectMapper.readTree(messages(token, conversationId));
        assertThat(json.get(1).get("status").asText()).isEqualTo("COMPLETE");
        assertThat(json.get(1).get("content").asText()).isEqualTo(REFUSAL);
    }

    @Test
    @DisplayName("a stranger cannot stream into someone else's conversation")
    void ownershipIsEnforced() throws Exception {
        String ownerToken = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(ownerToken, "private.pdf");
        UUID conversationId = createConversation(ownerToken, "Private stream");
        attach(ownerToken, conversationId, docId);

        String strangerToken = tokenForNewUser();

        mockMvc.perform(get("/api/v1/conversations/{id}/stream", conversationId)
                        .param("message", GROUNDED_QUERY)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("a blank message is rejected before the stream starts")
    void blankMessageRejected() throws Exception {
        String token = tokenForNewUser();
        UUID conversationId = createConversation(token, "Blank stream");

        mockMvc.perform(get("/api/v1/conversations/{id}/stream", conversationId)
                        .param("message", "   ")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("a client disconnect reconciles the assistant message to CANCELLED")
    void clientDisconnectMarksCancelled() throws Exception {
        String token = tokenForNewUser();
        UUID userId = currentUserId(token);
        UUID docId = uploadAndAwaitReady(token, "cancel.pdf");
        UUID conversationId = createConversation(token, "Cancel stream");
        attach(token, conversationId, docId);

        AuthenticatedUser user = new AuthenticatedUser(userId, "test@example.com", Role.USER);

        List<ServerSentEvent<Object>> received =
                chatService.stream(user, conversationId, GROUNDED_QUERY)
                        .take(1)
                        .collectList()
                        .block();

        assertThat(received).isNotNull();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).event()).isEqualTo("token");

        JsonNode json = objectMapper.readTree(messages(token, conversationId));
        assertThat(json.size()).isEqualTo(2);
        JsonNode assistant = json.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("ASSISTANT");
        assertThat(assistant.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(assistant.get("content").asText()).isNotBlank();
    }

    private String streamBody(String token, UUID conversationId, String message) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/conversations/{id}/stream", conversationId)
                        .param("message", message)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private String messages(String token, UUID conversationId) throws Exception {
        return mockMvc.perform(get("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void attach(String token, UUID conversationId, UUID documentId) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/documents", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest(
                                        List.of(documentId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(documentId.toString()));
    }

    private UUID createConversation(String token, String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.CreateConversationRequest(title))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID uploadAndAwaitReady(String token, String filename) throws Exception {
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());
        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String statusBody = mockMvc.perform(get("/api/v1/documents/{id}", id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String status = objectMapper.readTree(statusBody).get("status").asText();
            if (status.equals("READY")) {
                return id;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Document did not reach READY in time");
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Stream Test"))))
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
