package com.veridoc.ai.conversation.api;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end grounding chat: upload -> ingest -> attach -> ask, with the
 * deterministic chat/embedding stubs. Verifies the evidence contract (answer
 * grounded in retrieved chunks), the "no evidence" refusal, citations, and
 * strict ownership boundaries.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class ChatRagIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String GROUNDED_QUERY =
            "VeriDoc AI verifies claims against uploaded source documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("RAG turns publish retrieval, refusal and citation metrics")
    void ragPublishesTelemetry() throws Exception {
        Counter citations = meterRegistry.counter("veridoc.rag.citations");
        Counter refusals = meterRegistry.counter("veridoc.rag.refusals");
        Timer retrieval = meterRegistry.timer("veridoc.rag.retrieval");
        double citationsBefore = citations.count();
        double refusalsBefore = refusals.count();
        long retrievalsBefore = retrieval.count();

        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "contract.pdf");
        UUID conversationId = createConversation(token, "Telemetry");
        attach(token, conversationId, docId);

        ask(token, conversationId, GROUNDED_QUERY);
        ask(token, conversationId, "Why do zebras cross rivers safely?");

        assertThat(retrieval.count()).isGreaterThan(retrievalsBefore);
        assertThat(citations.count()).isGreaterThan(citationsBefore);
        assertThat(refusals.count()).isGreaterThan(refusalsBefore);
    }

    @Test
    @DisplayName("a grounded question answers with text and citations from the attached document")
    void groundedAnswerWithCitations() throws Exception {
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "contract.pdf");
        UUID conversationId = createConversation(token, "Contract Q&A");
        attach(token, conversationId, docId);

        JsonNode response = ask(token, conversationId, GROUNDED_QUERY);

        assertThat(response.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(response.get("content").asText()).contains("Based on the uploaded documents");
        assertThat(response.get("citations").isArray()).isTrue();
        assertThat(response.get("citations")).isNotEmpty();

        JsonNode firstCitation = response.get("citations").get(0);
        assertThat(firstCitation.get("documentFilename").asText()).isEqualTo("contract.pdf");
        assertThat(firstCitation.get("documentId").asText())
                .isEqualTo(docId.toString());
        assertThat(firstCitation.get("ordinal").asInt()).isEqualTo(1);
        assertThat(firstCitation.get("quotedText").asText())
                .contains("VeriDoc AI verifies claims");

        String messagesBody = mockMvc.perform(get("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(messagesBody).size()).isEqualTo(2);

        String detailBody = mockMvc.perform(get("/api/v1/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(docId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(detailBody).contains("contract.pdf");
    }

    @Test
    @DisplayName("an unsupported question returns the no-evidence answer with no citations")
    void unsupportedQuestionRefused() throws Exception {
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token, "manual.pdf");
        UUID conversationId = createConversation(token, "Manual chat");
        attach(token, conversationId, docId);

        JsonNode response = ask(token, conversationId, "Why do zebras cross rivers safely?");

        assertThat(response.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(response.get("content").asText())
                .isEqualTo("I couldn't find enough information in the uploaded documents to answer that.");
        assertThat(response.get("citations").isArray()).isTrue();
        assertThat(response.get("citations")).isEmpty();
    }

    @Test
    @DisplayName("a conversation without documents refuses to answer")
    void conversationWithoutDocumentsRefused() throws Exception {
        String token = tokenForNewUser();
        UUID conversationId = createConversation(token, "Empty chat");

        JsonNode response = ask(token, conversationId, "Is the sky blue?");

        assertThat(response.get("content").asText())
                .isEqualTo("I couldn't find enough information in the uploaded documents to answer that.");
        assertThat(response.get("citations")).isEmpty();
    }

    @Test
    @DisplayName("owning the conversation, the document, or both is required at every step")
    void ownershipIsEnforced() throws Exception {
        String ownerToken = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(ownerToken, "private.pdf");
        UUID conversationId = createConversation(ownerToken, "Private");
        attach(ownerToken, conversationId, docId);

        String strangerToken = tokenForNewUser();

        mockMvc.perform(get("/api/v1/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.ChatRequest(GROUNDED_QUERY))))
                .andExpect(status().isNotFound());

        UUID strangerConversation = createConversation(strangerToken, "Stranger");
        mockMvc.perform(post("/api/v1/conversations/{id}/documents", strangerConversation)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest(
                                        List.of(docId)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_NOT_FOUND.name()));

        String listBody = mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("\"Private\"");
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
                                        email, PASSWORD, "Chat Test"))))
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