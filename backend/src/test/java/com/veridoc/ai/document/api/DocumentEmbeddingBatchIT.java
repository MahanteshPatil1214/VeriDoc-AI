package com.veridoc.ai.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.DeterministicEmbeddingModel;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof that document ingestion uses the configured batch size and
 * that a mid-ingest provider failure leaves no chunks behind.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "veridoc.embedding.batch-size=2")
@Import(DocumentEmbeddingBatchIT.ControlledEmbeddingConfig.class)
class DocumentEmbeddingBatchIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ControlledEmbeddingModel embeddingModel;

    @BeforeEach
    void resetEmbeddingModel() {
        embeddingModel.reset();
    }

    @Test
    @DisplayName("multi-batch ingestion persists every chunk in order")
    void multiBatchIngestionPersistsAllChunks() throws Exception {
        String token = tokenForNewUser();
        UUID id = upload(token, "batched.pdf",
                TestPdfs.pages(pageText("one"), pageText("two"), pageText("three")));

        JsonNode completed = awaitStatus(token, id, n ->
                n.get("status").asText().equals("READY")
                        || n.get("status").asText().equals("FAILED"));

        assertThat(completed.get("status").asText()).isEqualTo("READY");
        assertThat(completed.get("pageCount").asInt()).isEqualTo(3);
        assertThat(completed.get("chunkCount").asInt()).isEqualTo(3);
        assertThat(embeddingModel.batchSizes()).containsExactly(2, 1);

        List<Map<String, Object>> chunks = jdbcTemplate.queryForList("""
                SELECT chunk_index, content, page_number, vector_dims(embedding) AS dims
                FROM document_chunks
                WHERE document_id = ?
                ORDER BY chunk_index
                """, id);
        assertThat(chunks).hasSize(3);
        assertThat(chunks)
                .extracting(row -> ((Number) row.get("chunk_index")).intValue())
                .containsExactly(0, 1, 2);
        assertThat(chunks)
                .extracting(row -> ((Number) row.get("page_number")).intValue())
                .containsExactly(1, 2, 3);
        assertThat(chunks)
                .extracting(row -> ((Number) row.get("dims")).intValue())
                .containsOnly(768);
        assertThat((String) chunks.get(0).get("content")).contains("Page one");
        assertThat((String) chunks.get(1).get("content")).contains("Page two");
        assertThat((String) chunks.get(2).get("content")).contains("Page three");
    }

    @Test
    @DisplayName("a later batch failure marks the document FAILED and persists no chunks")
    void laterBatchFailureDoesNotPersistPartialDocument() throws Exception {
        embeddingModel.failOnCall(2);
        String token = tokenForNewUser();
        UUID id = upload(token, "failing.pdf",
                TestPdfs.pages(pageText("one"), pageText("two"), pageText("three")));

        JsonNode failed = awaitStatus(token, id, n ->
                n.get("status").asText().equals("FAILED"));

        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).isNotBlank();
        assertThat(embeddingModel.batchSizes()).containsExactly(2, 1);

        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE document_id = ?", Integer.class, id);
        assertThat(chunkCount).isZero();
    }

    private UUID upload(String token, String filename, byte[] pdf) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private JsonNode awaitStatus(String token, UUID id,
                                 java.util.function.Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
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
                                        email, PASSWORD, "Batch Test"))))
                .andExpect(status().isCreated());

        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }

    private static String pageText(String page) {
        return "Page " + page + " records the retention policy for archived records. "
                + "Retention rules require archived documents to remain available for seven years. "
                + "Access reviews happen every quarter and must be documented by the owner. "
                + "Deletion requests are reviewed by compliance before any records are removed. "
                + "This paragraph is long enough to produce one chunk for the test.";
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ControlledEmbeddingConfig {

        @Bean
        @Primary
        ControlledEmbeddingModel controlledEmbeddingModel() {
            return new ControlledEmbeddingModel();
        }
    }

    static final class ControlledEmbeddingModel extends DeterministicEmbeddingModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        private volatile int failOnCall = -1;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            batchSizes.add(request.getInstructions().size());
            int call = calls.incrementAndGet();
            if (call == failOnCall) {
                throw new AppException(ErrorCode.EMBEDDING_FAILED,
                        "Simulated embedding provider failure");
            }
            return super.call(request);
        }

        void reset() {
            calls.set(0);
            batchSizes.clear();
            failOnCall = -1;
        }

        void failOnCall(int call) {
            failOnCall = call;
        }

        List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }
    }
}
