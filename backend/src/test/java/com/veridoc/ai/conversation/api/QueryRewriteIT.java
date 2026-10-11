package com.veridoc.ai.conversation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.RewriteAwareChatModel;
import com.veridoc.ai.support.RewriteTestConfig;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves {@code veridoc.rag.enable-query-rewriting} is wired all the way
 * through: with rewriting on, every retrieval first asks the chat model for a
 * standalone query (the stub records the call), and the grounded answer is then
 * produced from passages retrieved under the rewritten query.
 */
@AutoConfigureMockMvc
@Import(RewriteTestConfig.class)
class QueryRewriteIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String GROUNDED_QUERY =
            "VeriDoc AI verifies claims against uploaded source documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void enableRewriting(DynamicPropertyRegistry registry) {
        // Higher precedence than application-test.yml, which keeps it disabled.
        registry.add("veridoc.rag.enable-query-rewriting", () -> "true");
    }

    @Test
    @DisplayName("the question is rewritten before retrieval and the answer stays grounded")
    void queryIsRewrittenBeforeRetrieval() throws Exception {
        RewriteAwareChatModel.reset();
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "contract.pdf");
        UUID conversationId = createConversation(token, "Rewrite");
        attach(token, conversationId, docId);

        JsonNode response = ask(token, conversationId, GROUNDED_QUERY);

        assertThat(RewriteAwareChatModel.rewriteSeen()).isTrue();
        assertThat(response.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(response.get("content").asText()).contains("Based on the uploaded documents");
        assertThat(response.get("citations").isArray()).isTrue();
        assertThat(response.get("citations")).isNotEmpty();
        assertThat(response.get("citations").get(0).get("documentFilename").asText())
                .isEqualTo("contract.pdf");
    }

    private JsonNode ask(String token, UUID conversationId, String message) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.ChatRequest(message))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
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

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Rewrite Test"))))
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