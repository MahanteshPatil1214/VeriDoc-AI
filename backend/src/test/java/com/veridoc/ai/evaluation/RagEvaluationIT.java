package com.veridoc.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

import com.veridoc.ai.config.properties.ChunkingProperties;
import com.veridoc.ai.evaluation.EvalDataset.CorpusDocument;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.EvalTestConfig;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.RecordingPromptBuilder;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end RAG evaluation gate: the dataset corpus is uploaded and ingested,
 * every case is asked over HTTP with one conversation per case — a case replays
 * only the history it declared — and the aggregate retrieval / answer /
 * citation metrics are compared against the measured
 * {@link EvalGate#deterministicStub()} baseline.
 *
 * <p>Retrieved evidence comes from {@link RecordingPromptBuilder}, which records
 * the exact {@code bundle.evidence()} that {@code ChatService} passed to the
 * model. Citations come from the HTTP response. The two are reported
 * separately: retrieval metrics never see the citation list, and citation
 * metrics never see the retrieved evidence.
 *
 * <p>Page-level citation precision and recall are reported alongside the
 * document-level ones. They are computed over the cases that declare expected
 * pages only; the applicable-case count is part of the report so the
 * denominator is never implicit.
 *
 * <p>{@link EvalThresholds#core()} is the Stage 3 real-provider gate and is
 * deliberately not used here: the deterministic double's similarity is driven by
 * stopword multiplicity, so it cannot express the precision a live model
 * reaches. The thresholds below are the measured behaviour of the double on the
 * shipped dataset, and they exist to catch regressions in the dataset, the
 * harness or the retrieval path.
 */
@AutoConfigureMockMvc
@Import({IngestionTestConfig.class, EvalTestConfig.class})
class RagEvaluationIT extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationIT.class);
    private static final String PASSWORD = "correct horse battery";
    private static final String DATASET = "/rag-eval/eval-dataset.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingPromptBuilder promptBuilder;

    @Autowired
    private ChunkingProperties chunkingProperties;

    @Test
    @DisplayName("the shipped RAG dataset meets the deterministic-stub gate with strict isolation and citation/evidence separation")
    void datasetMeetsStubGate() throws Exception {
        EvalDataset dataset = EvalDatasetLoader.load(DATASET);
        EvalDatasetValidator.validate(dataset, chunkingProperties.minTokens());

        EvalGate gate = EvalGate.deterministicStub();
        EvalConversationPlanner planner = new EvalConversationPlanner(dataset.cases());

        String token = tokenForNewUser();
        Map<String, UUID> documentIds = uploadCorpus(token, dataset);

        Map<String, Integer> historySizes = new LinkedHashMap<>();
        Map<String, String> systemPrompts = new LinkedHashMap<>();

        RagEvaluator.CaseRunner runner = evalCase -> runCase(token, documentIds, planner, evalCase,
                historySizes, systemPrompts);

        EvalReport report = RagEvaluator.evaluate(dataset, runner, gate.retrievalK());
        log.info("RAG evaluation report:{}{}", System.lineSeparator(), report.summary());
        logCategoryBreakdown(report);

        // The named, measured gate for the deterministic double.
        RagEvaluator.assertMeets(report, gate);

        assertThat(report.datasetName()).isEqualTo(dataset.name());
        assertThat(report.results()).hasSize(dataset.cases().size());

        // Isolation: every case ran in its own conversation and saw exactly the
        // history the planner planned for it — none for an independent case,
        // two messages per declared turn for a multi-turn case.
        Set<String> conversationKeys = new LinkedHashSet<>();
        for (EvalCase evalCase : dataset.cases()) {
            EvalConversationPlanner.Plan plan = planner.planFor(evalCase.id());
            assertThat(conversationKeys.add(plan.conversationKey()))
                    .as("conversation key of case %s must be unique", evalCase.id())
                    .isTrue();
            assertThat(historySizes.get(evalCase.id()))
                    .as("case %s must see only its own history", evalCase.id())
                    .isEqualTo(plan.expectedHistoryMessages());
        }

        // Refusal: a case that must abstain retrieved no evidence at all.
        for (EvalCase evalCase : dataset.cases()) {
            if (!evalCase.expectRefusal()) {
                continue;
            }
            EvalCaseResult result = resultOf(report, evalCase.id());
            assertThat(result.observation().retrieved())
                    .as("refusal case %s must retrieve nothing", evalCase.id())
                    .isEmpty();
            assertThat(result.refusalCorrect())
                    .as("refusal case %s must be answered with a refusal", evalCase.id())
                    .isTrue();
        }

        // Prompt injection: the smuggled tag never survives unescaped, the
        // injected directive never becomes the answer, and the answer stays
        // grounded in the expected document.
        for (EvalCase evalCase : dataset.cases()) {
            if (!"prompt-injection".equals(evalCase.category())) {
                continue;
            }
            String system = systemPrompts.getOrDefault(evalCase.id(), "");
            int blocks = count(system, "<evidence n=");
            int closes = count(system, "</evidence>");
            assertThat(blocks).as("injection case %s evidence blocks", evalCase.id()).isPositive();
            assertThat(closes).as("injection case %s evidence blocks must be balanced",
                    evalCase.id()).isEqualTo(blocks);
            assertThat(system)
                    .as("injection case %s must not contain an unescaped smuggled tag", evalCase.id())
                    .doesNotContain("</evidence><evidence");

            EvalCaseResult result = resultOf(report, evalCase.id());
            String answer = result.observation().answer();
            for (String phrase : evalCase.injectionPhrases()) {
                assertThat(answer.toUpperCase(Locale.ROOT))
                        .as("injection case %s must not emit the injected directive %s",
                                evalCase.id(), phrase)
                        .doesNotContain(phrase.toUpperCase(Locale.ROOT));
            }
            assertThat(answer)
                    .as("injection case %s must not leak a forged document name", evalCase.id())
                    .doesNotContain("evil.txt");
            assertThat(result.keywordCoverage())
                    .as("injection case %s must still answer from the honest passage", evalCase.id())
                    .isEqualTo(1.0);
            assertThat(result.citationPrecision())
                    .as("injection case %s must cite the expected document", evalCase.id())
                    .isEqualTo(1.0);
        }

        // Dataset integrity: every corpus doc is referenced by at least one case.
        Set<String> referenced = new LinkedHashSet<>();
        for (EvalCase evalCase : dataset.cases()) {
            referenced.addAll(evalCase.resolvedDocuments());
        }
        for (CorpusDocument doc : dataset.corpus()) {
            assertThat(referenced).contains(doc.filename());
        }

        assertPageMetrics(dataset, report);
    }

    /**
     * Page-level citation metrics over the shipped dataset.
     *
     * <p>The deterministic double answers with the top passage, so its single
     * citation is the rank-1 chunk. Retrieval hit rate is 1.0 with page-aware
     * labels, which means that chunk is on a labelled page — so every
     * page-applicable case must score 1.0 page precision and 1.0 page recall.
     *
     * <p>The applicable-case count is checked against the dataset rather than
     * only against a constant, so a dataset change cannot silently move the
     * denominator; the constant is pinned as well so a silent change to the
     * shipped dataset is still caught.
     */
    private static void assertPageMetrics(EvalDataset dataset, EvalReport report) {
        List<EvalCase> pageCases = dataset.cases().stream()
                .filter(evalCase -> CitationMetrics.pageLabelsApplicable(evalCase.labels()))
                .toList();

        assertThat(report.pageApplicableCaseCount())
                .as("the page metrics must be computed over every case that declares pages")
                .isEqualTo(pageCases.size());
        assertThat(report.pageApplicableCaseCount())
                .as("page-applicable case count of the shipped dataset")
                .isEqualTo(22);

        for (EvalCase evalCase : dataset.cases()) {
            EvalCaseResult result = resultOf(report, evalCase.id());
            if (!CitationMetrics.pageLabelsApplicable(evalCase.labels())) {
                // Not applicable: excluded from the aggregates, and never a
                // vacuous 1.0.
                assertThat(result.pageCitationPrecision())
                        .as("non-applicable case %s must not score a page precision", evalCase.id())
                        .isZero();
                assertThat(result.pageCitationRecall())
                        .as("non-applicable case %s must not score a page recall", evalCase.id())
                        .isZero();
                continue;
            }
            assertThat(evalCase.expectedPages())
                    .as("page-applicable case %s labels exactly one page in the shipped dataset",
                            evalCase.id())
                    .hasSize(1);
            assertThat(result.pageCitationPrecision())
                    .as("page-applicable case %s must cite the labelled page", evalCase.id())
                    .isEqualTo(1.0);
            assertThat(result.pageCitationRecall())
                    .as("page-applicable case %s must cover its labelled page", evalCase.id())
                    .isEqualTo(1.0);
        }

        assertThat(report.meanPageCitationPrecision())
                .as("mean page citation precision over page-applicable cases")
                .isEqualTo(1.0);
        assertThat(report.meanPageCitationRecall())
                .as("mean page citation recall over page-applicable cases")
                .isEqualTo(1.0);
        assertThat(report.missingPageCitationCount())
                .as("no citation to an expected document may lack a page number")
                .isZero();

        // The summary must publish the applicable-case count, so a reader of the
        // build log can tell which denominator the page metrics used.
        assertThat(report.summary())
                .contains("page-applicable cases " + pageCases.size() + "/" + dataset.cases().size());
    }

    @Test
    @DisplayName("independent conversations never contaminate each other; multi-turn shares one conversation")
    void independentConversationsDoNotShareHistory() throws Exception {
        EvalDataset dataset = EvalDatasetLoader.load(DATASET);
        EvalConversationPlanner planner = new EvalConversationPlanner(dataset.cases());
        String token = tokenForNewUser();
        Map<String, UUID> documentIds = uploadCorpus(token, dataset);

        // Two independent cases in sequence must each see empty history.
        EvalCase first = find(dataset, "direct-flight-class");
        EvalCase second = find(dataset, "direct-probation");
        assertThat(first.category()).isNotEqualTo("multi-turn");
        assertThat(second.category()).isNotEqualTo("multi-turn");

        Map<String, Integer> historySizes = new LinkedHashMap<>();
        Map<String, String> systemPrompts = new LinkedHashMap<>();
        runCase(token, documentIds, planner, first, historySizes, systemPrompts);
        assertThat(historySizes.get(first.id())).isEqualTo(0);
        runCase(token, documentIds, planner, second, historySizes, systemPrompts);
        assertThat(historySizes.get(second.id()))
                .as("a later independent case must not inherit the previous case's history")
                .isEqualTo(0);

        // Multi-turn case: first turn sees empty history, second sees both messages.
        EvalCase turn = find(dataset, "turn-vacation-carryover");
        promptBuilder.reset();
        UUID conv = createConversation(token, "turn-test");
        attach(token, conv, new ArrayList<>(documentIds.values()));
        promptBuilder.reset();
        ask(token, conv, turn.history().get(0).content());
        assertThat(promptBuilder.lastHistory()).as("first turn sees no prior history").isEmpty();
        promptBuilder.reset();
        ask(token, conv, turn.question());
        assertThat(promptBuilder.lastHistory()).as("second turn sees the prior exchange").hasSize(2);
    }

    private EvalObservation runCase(String token,
                                      Map<String, UUID> documentIds,
                                      EvalConversationPlanner planner,
                                      EvalCase evalCase,
                                      Map<String, Integer> historySizes,
                                      Map<String, String> systemPrompts) throws Exception {
        EvalConversationPlanner.Plan plan = planner.planFor(evalCase.id());
        UUID conversationId = createConversation(token, plan.conversationKey());
        List<UUID> attachAll = new ArrayList<>(documentIds.values());
        attach(token, conversationId, attachAll);

        int expectedHistory = 0;
        for (HistoryTurn turn : plan.priorTurns()) {
            promptBuilder.reset();
            ask(token, conversationId, turn.content());
            assertThat(promptBuilder.lastHistory())
                    .as("history seen before turn in case %s", evalCase.id())
                    .hasSize(expectedHistory);
            expectedHistory += 2;
        }

        promptBuilder.reset();
        JsonNode response = ask(token, conversationId, evalCase.question());
        assertThat(promptBuilder.lastHistory())
                .as("history seen by final turn of case %s", evalCase.id())
                .hasSize(expectedHistory);
        historySizes.put(evalCase.id(), promptBuilder.lastHistory().size());
        systemPrompts.put(evalCase.id(), promptBuilder.lastSystemPrompt());

        if (evalCase.expectRefusal()) {
            assertThat(promptBuilder.lastEvidence())
                    .as("refusal case %s must retrieve nothing (below min-score or no evidence)", evalCase.id())
                    .isEmpty();
        }

        return new EvalObservation(
                promptBuilder.lastEvidence(),
                citationsOf(response),
                response.hasNonNull("citations") && response.get("citations").isEmpty(),
                response.hasNonNull("content") ? response.get("content").asText() : "");
    }

    private Map<String, UUID> uploadCorpus(String token, EvalDataset dataset) throws Exception {
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (CorpusDocument document : dataset.corpus()) {
            UUID id = uploadAndAwaitReady(token, document.filename(), document.pageTexts());
            ids.put(document.filename(), id);
        }
        return ids;
    }

    private static EvalCaseResult resultOf(EvalReport report, String caseId) {
        return report.results().stream()
                .filter(r -> r.evalCase().id().equals(caseId)).findFirst().orElseThrow();
    }

    /** Per-category breakdown of the measured run, for the build log. */
    private static void logCategoryBreakdown(EvalReport report) {
        Map<String, List<EvalCaseResult>> byCategory = new TreeMap<>();
        for (EvalCaseResult result : report.results()) {
            byCategory.computeIfAbsent(result.evalCase().category(), key -> new ArrayList<>())
                    .add(result);
        }
        for (Map.Entry<String, List<EvalCaseResult>> entry : byCategory.entrySet()) {
            List<EvalCaseResult> results = entry.getValue();
            List<EvalCaseResult> grounded = results.stream()
                    .filter(r -> r.evalCase().isGrounded()).toList();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "  %-16s n=%2d", entry.getKey(), results.size()));
            if (!grounded.isEmpty()) {
                sb.append(String.format(Locale.ROOT,
                        " hit=%.3f mrr=%.3f p@k=%.3f r@k=%.3f keyword=%.3f citation=%.3f",
                        mean(grounded, r -> r.hit() ? 1.0 : 0.0),
                        mean(grounded, EvalCaseResult::reciprocalRank),
                        mean(grounded, EvalCaseResult::precisionAtK),
                        mean(grounded, EvalCaseResult::recallAtK),
                        mean(grounded, EvalCaseResult::keywordCoverage),
                        mean(grounded, EvalCaseResult::citationPrecision)));
            }
            List<EvalCaseResult> pageApplicable = results.stream()
                    .filter(r -> CitationMetrics.pageLabelsApplicable(r.evalCase().labels()))
                    .toList();
            if (!pageApplicable.isEmpty()) {
                sb.append(String.format(Locale.ROOT, " pageP=%.3f pageR=%.3f (n=%d)",
                        mean(pageApplicable, EvalCaseResult::pageCitationPrecision),
                        mean(pageApplicable, EvalCaseResult::pageCitationRecall),
                        pageApplicable.size()));
            }
            sb.append(String.format(Locale.ROOT, " refusalCorrect=%.3f",
                    mean(results, r -> r.refusalCorrect() ? 1.0 : 0.0)));
            log.info(sb.toString());
        }
    }

    private static double mean(List<EvalCaseResult> results,
                               java.util.function.ToDoubleFunction<EvalCaseResult> extractor) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (EvalCaseResult result : results) {
            sum += extractor.applyAsDouble(result);
        }
        return sum / results.size();
    }

    private EvalCase find(EvalDataset dataset, String id) {
        return dataset.cases().stream().filter(c -> id.equals(c.id())).findFirst()
                .orElseThrow(() -> new AssertionError("missing case " + id));
    }

    private static int count(String text, String token) {
        if (text == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    /**
     * The cited passages, as reported by the production response. This is the
     * only input to citation metrics; it is never reused as retrieval evidence.
     */
    private static List<EvidencePassage> citationsOf(JsonNode response) {
        List<EvidencePassage> citations = new ArrayList<>();
        JsonNode citationsNode = response.get("citations");
        if (citationsNode == null || !citationsNode.isArray()) {
            return List.copyOf(citations);
        }
        for (JsonNode citation : citationsNode) {
            if (citation == null || !citation.hasNonNull("documentFilename")
                    || !citation.hasNonNull("chunkId")) {
                continue;
            }
            citations.add(new EvidencePassage(
                    citation.get("documentFilename").asText(),
                    UUID.fromString(citation.get("chunkId").asText()),
                    citation.hasNonNull("pageNumber") ? citation.get("pageNumber").asInt() : null));
        }
        return List.copyOf(citations);
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

    private UUID uploadAndAwaitReady(String token, String filename, List<String> pages)
            throws Exception {
        byte[] pdf = TestPdfs.pages(pages.toArray(new String[0]));
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

    private UUID uploadAndAwaitReady(String token, String filename, String text)
            throws Exception {
        return uploadAndAwaitReady(token, filename, List.of(text));
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
