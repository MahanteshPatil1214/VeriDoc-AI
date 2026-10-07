package com.veridoc.ai.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Upload + ingest path against a real pgvector database. The embedding client
 * is the deterministic stub (see {@link IngestionTestConfig}); Gemini is never
 * contacted.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class DocumentUploadIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("uploading a PDF ingests it: EXTRACTING..READY with pgvector chunks")
    void uploadProcessesToReady() throws Exception {
        String token = tokenForNewUser();
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.filename").value("manual.pdf"))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        JsonNode status = awaitReady(token, id, 20_000);
        assertThat(status.get("status").asText()).isEqualTo("READY");
        assertThat(status.get("pageCount").asInt()).isEqualTo(1);
        assertThat(status.get("chunkCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(status.get("error").isNull()).isTrue();

        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE document_id = ?", Integer.class, id);
        assertThat(chunkCount).isEqualTo(status.get("chunkCount").asInt());

        Integer dims = jdbcTemplate.queryForObject(
                "SELECT DISTINCT vector_dims(embedding) FROM document_chunks WHERE document_id = ?",
                Integer.class, id);
        assertThat(dims).isEqualTo(768);

        String listBody = mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).contains("\"manual.pdf\"");
    }

    @Test
    @DisplayName("a corrupt PDF ends in FAILED with an error message")
    void corruptPdfFails() throws Exception {
        String token = tokenForNewUser();

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "broken.pdf", "application/pdf",
                                "this is not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        JsonNode status = awaitStatus(token, id, 20_000, n -> n.get("status").asText().equals("FAILED"));
        assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("error").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a second upload of identical bytes is rejected with CONFLICT")
    void duplicateContentRejected() throws Exception {
        String token = tokenForNewUser();
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "b.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.name()));
    }

    @Test
    @DisplayName("documents are owner-isolated: another user cannot read or list them")
    void ownerIsolation() throws Exception {
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());
        String ownerToken = tokenForNewUser();

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "private.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        awaitReady(ownerToken, id, 20_000);

        String otherToken = tokenForNewUser();

        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));

        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("\"private.pdf\""));
    }

    private JsonNode awaitReady(String token, UUID id, long timeoutMs) throws Exception {
        return awaitStatus(token, id, timeoutMs,
                n -> n.get("status").asText().equals("READY")
                        || n.get("status").asText().equals("FAILED"));
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
        throw new AssertionError("Document " + id + " did not reach the expected state. Last status: " + last);
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "Upload Test"))))
                .andExpect(status().isCreated());

        String login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }
}