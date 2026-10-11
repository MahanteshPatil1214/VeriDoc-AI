package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end RAG evaluation gate: the dataset corpus is uploaded and ingested,
 * every case is asked over HTTP, and the aggregate retrieval / answer / citation
 * metrics must clear {@link EvalThresholds#core()}. A regression that breaks
 * grounding, ranking, or the refusal contract fails the build here.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class RagEvaluationIT extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationIT.class);
    private static final String PASSWORD = "correct horse battery";
    private static final String DATASET = "/rag-eval/eval-dataset.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the shipped RAG dataset clears the core evaluation thresholds")
    void datasetMeetsCoreThresholds() throws Exception {
        EvalDataset dataset = EvalDatasetLoader.load(DATASET);
        EvalThresholds thresholds = EvalThresholds.core();

        String token = tokenForNewUser();
        List<UUID> documentIds = new ArrayList<>();
        for (CorpusDocument document : dataset.corpus()) {
            documentIds.add(uploadAndAwaitReady(token, document.filename(), document.text()));
        }

        UUID conversationId = createConversation(token, "RAG evaluation");
        attach(token, conversationId, documentIds);

        RagEvaluator.CaseRunner runner = evalCase -> {
            JsonNode response = ask(token, conversationId, evalCase.question());
            List<String> ranked = new ArrayList<>();
            for (JsonNode citation : response.get("citations")) {
                ranked.add(citation.get("documentFilename").asText());
            }
            return new EvalObservation(ranked, ranked.isEmpty(),
                    response.get("content").asText());
        };

        EvalReport report = RagEvaluator.evaluate(dataset, runner, thresholds.retrievalK());
        log.info("RAG evaluation report:{}{}", System.lineSeparator(), report.summary());

        RagEvaluator.assertMeets(report, thresholds);

        assertThat(report.hitRate()).isEqualTo(1.0);
        assertThat(report.refusalCorrectness()).isEqualTo(1.0);
        assertThat(report.meanReciprocalRank()).isGreaterThanOrEqualTo(0.99);
    }

    private void attach(String token, UUID conversationId, List<UUID> documentIds)
            throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/documents", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest(
                                        documentIds))))
                .andExpect(status().isOk());
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

    private UUID uploadAndAwaitReady(String token, String filename, String text)
            throws Exception {
        byte[] pdf = TestPdfs.singlePage(text);
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
        throw new AssertionError("Document " + filename + " did not reach READY in time");
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "RAG Eval"))))
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
