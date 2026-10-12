package com.veridoc.ai.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Document delete + retry: owner scoping, soft-delete semantics (vectors dropped,
 * hidden from listings, duplicate upload unblocked) and the FAILED-only retry
 * guard.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class DocumentLifecycleIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("deleting a READY document drops its vectors, hides it and frees a re-upload")
    void deleteRemovesDocumentAndVectors() throws Exception {
        String token = tokenForNewUser();
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());
        UUID id = upload(token, "policy.pdf", pdf);
        awaitStatus(token, id, 20_000, n -> n.get("status").asText().equals("READY"));

        mockMvc.perform(delete("/api/v1/documents/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        String statusBody = mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(statusBody).get("status").asText()).isEqualTo("DELETED");

        String listBody = mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("policy.pdf");

        Integer chunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE document_id = ?", Integer.class, id);
        assertThat(chunks).isZero();

        // Duplicate detection ignores DELETED rows, so the same bytes upload again.
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("delete is idempotent and owner-scoped")
    void deleteIsIdempotentAndScoped() throws Exception {
        String owner = tokenForNewUser();
        UUID id = upload(owner, "private.pdf", TestPdfs.singlePage(TestPdfs.longEnoughParagraph()));
        awaitStatus(owner, id, 20_000, n -> n.get("status").asText().equals("READY"));

        String other = tokenForNewUser();
        mockMvc.perform(delete("/api/v1/documents/{id}", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_NOT_FOUND.name()));

        mockMvc.perform(delete("/api/v1/documents/{id}", id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        // Second delete is a no-op, not a 404.
        mockMvc.perform(delete("/api/v1/documents/{id}", id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("retry is rejected unless the document is FAILED")
    void retryRejectedWhenNotFailed() throws Exception {
        String token = tokenForNewUser();
        UUID id = upload(token, "manual.pdf", TestPdfs.singlePage(TestPdfs.longEnoughParagraph()));
        awaitStatus(token, id, 20_000, n -> n.get("status").asText().equals("READY"));

        mockMvc.perform(post("/api/v1/documents/{id}/retry", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.name()));
    }

    @Test
    @DisplayName("retry cannot be triggered against another user's document")
    void retryIsOwnerScoped() throws Exception {
        String owner = tokenForNewUser();
        UUID id = upload(owner, "broken.pdf", corruptPdf());
        awaitStatus(owner, id, 20_000, n -> n.get("status").asText().equals("FAILED"));

        String other = tokenForNewUser();
        mockMvc.perform(post("/api/v1/documents/{id}/retry", id)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("a failed document can be retried and re-enters the pipeline")
    void retryReentersPipeline() throws Exception {
        String token = tokenForNewUser();
        UUID id = upload(token, "broken.pdf", corruptPdf());
        awaitStatus(token, id, 20_000, n -> n.get("status").asText().equals("FAILED"));

        String body = mockMvc.perform(post("/api/v1/documents/{id}/retry", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The retry re-enters the pipeline immediately.
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("PROCESSING");

        // It fails again for the same structural reason, but must not be stuck.
        awaitStatus(token, id, 20_000, n -> n.get("status").asText().equals("FAILED"));
    }

    private byte[] corruptPdf() {
        return "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.UTF_8);
    }

    private UUID upload(String token, String filename, byte[] pdf) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private JsonNode awaitStatus(String token, UUID id, long timeoutMs,
                                 java.util.function.Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get("/api/v1/documents/{id}", id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            last = objectMapper.readTree(body);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Document " + id + " did not reach the expected state. Last: " + last);
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Lifecycle Test"))))
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
