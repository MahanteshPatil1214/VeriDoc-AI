package com.veridoc.ai.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.ObjectMapper;

/**
 * The admin RAG debugger exposes one retrieval pass: rewritten query, scored
 * passages and the grounding prompt. It is ADMIN-only and off unless
 * {@code veridoc.rag.debug-enabled=true}.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class RagDebugIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String GROUNDED_QUERY =
            "VeriDoc AI verifies claims against uploaded source documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("an admin sees the scored evidence and the grounding prompt")
    void adminSeesRetrievalEvidence() throws Exception {
        String token = adminToken();
        UUID docId = uploadAndAwaitReady(token, "contract.pdf");
        UUID conversationId = createConversation(token, "Debug chat");
        attach(token, conversationId, docId);

        String body = mockMvc.perform(get("/api/v1/admin/rag/debug")
                        .param("conversationId", conversationId.toString())
                        .param("question", GROUNDED_QUERY)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refusal").value(false))
                .andExpect(jsonPath("$.evidence").isNotEmpty())
                .andExpect(jsonPath("$.evidence[0].documentFilename").value("contract.pdf"))
                .andExpect(jsonPath("$.evidence[0].relevanceScore").isNumber())
                .andExpect(jsonPath("$.systemPrompt").value(org.hamcrest.Matchers.containsString("Passages:")))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("rewrittenQuery").asText())
                .isEqualTo(GROUNDED_QUERY);
    }

    @Test
    @DisplayName("a conversation with no evidence reports a refusal and no prompt")
    void refusalWhenNoEvidence() throws Exception {
        String token = adminToken();
        UUID conversationId = createConversation(token, "Empty chat");

        mockMvc.perform(get("/api/v1/admin/rag/debug")
                        .param("conversationId", conversationId.toString())
                        .param("question", "Anything at all?")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refusal").value(true))
                .andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.systemPrompt").doesNotExist());
    }

    @Test
    @DisplayName("a regular user is forbidden and anonymous callers are unauthorized")
    void authorizationIsEnforced() throws Exception {
        String userToken = userToken();
        UUID conversationId = createConversation(userToken, "User chat");

        mockMvc.perform(get("/api/v1/admin/rag/debug")
                        .param("conversationId", conversationId.toString())
                        .param("question", GROUNDED_QUERY)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        mockMvc.perform(get("/api/v1/admin/rag/debug")
                        .param("conversationId", conversationId.toString())
                        .param("question", GROUNDED_QUERY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()));
    }

    private String adminToken() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        register(email, "RAG Admin");
        int updated = jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN' WHERE lower(email) = ?", email);
        assertThat(updated).isEqualTo(1);
        return login(email);
    }

    private String userToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        register(email, "RAG User");
        return login(email);
    }

    private void register(String email, String displayName) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, displayName))))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private void attach(String token, UUID conversationId, UUID documentId) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/documents", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest(
                                        List.of(documentId)))))
                .andExpect(status().isOk());
    }

    private UUID createConversation(String token, String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.CreateConversationRequest(title))))
                .andExpect(status().isCreated())
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
            if (objectMapper.readTree(statusBody).get("status").asText().equals("READY")) {
                return id;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Document did not reach READY in time");
    }
}