package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ConfigurableApplicationContext;

import com.veridoc.ai.VeriDocApplication;
import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.ChunkingProperties;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.config.properties.RetrievalProperties;
import com.veridoc.ai.conversation.application.ConversationService;
import com.veridoc.ai.document.application.DocumentService;
import com.veridoc.ai.document.persistence.DocumentChunkRepository;
import com.veridoc.ai.document.persistence.DocumentRepository;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalDatasetLoader;
import com.veridoc.ai.infrastructure.vector.PgVectorChunkRepository;
import com.veridoc.ai.message.persistence.MessageRepository;
import com.veridoc.ai.rag.ChatService;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.support.EvalTestConfig;
import com.veridoc.ai.support.RecordingPromptBuilder;
import com.veridoc.ai.user.application.UserService;
import com.veridoc.ai.user.domain.User;

/**
 * The live-provider evaluation harness.
 *
 * <p><strong>The class name deliberately does not end in {@code IT}.</strong>
 * Failsafe's include list is {@code **&#47;*IT.java}, so the default build
 * cannot execute this class: neither {@code mvn test} (Surefire only owns
 * {@code *Test}) nor {@code mvn verify} (Failsafe only owns {@code *IT}) will
 * pick it up. The class is added to Failsafe's include list only by the
 * {@code live-eval} Maven profile, which is never active by default. That is
 * the structural half of the protection.
 *
 * <p>The behavioural half is the readiness preflight inherited from
 * {@link LiveEvaluationTestBase}: a run additionally needs
 * {@code -Dveridoc.eval.live=true}, both real credentials and a budget that
 * parses, and refuses to happen when the {@code CI} environment variable is
 * set. Both halves are independent, so neither a stale profile nor a stray
 * {@code -D} flag alone can start a paid run.
 *
 * <p>A measured run therefore needs all three:
 *
 * <pre>
 * mvn -B -Plive-eval -Dveridoc.eval.live=true \
 *     -Dit.test=com.veridoc.ai.evaluation.live.LiveProviderEvaluation \
 *     test-compile failsafe:integration-test failsafe:verify
 * </pre>
 *
 * <p>The measured run ingests the full corpus through the production upload
 * path into a throwaway PostgreSQL, runs the 10 selected cases through the
 * production chat pipeline and writes a redacted JSON and text report under
 * {@code target/live-eval/}. The report is descriptive: it does not apply a
 * quality gate and does not claim semantic faithfulness.
 */
class LiveProviderEvaluation extends LiveEvaluationTestBase {

    private static final String DATASET = "/rag-eval/eval-dataset.json";

    @Test
    @DisplayName("the live run is ready: activated, credentialed and inside a fresh budget")
    void liveRunIsReady() {
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady();

        assertThat(plan.activation().enabled())
                .as("the inherited @BeforeAll preflight must have rejected this run otherwise")
                .isTrue();
        assertThat(plan.activation().violations()).isEmpty();
        assertThat(plan.credentials().acceptable())
                .as("presence checked, validity still unproven: %s", plan.credentials().describe())
                .isTrue();
        assertThat(plan.budget().maxChatCalls()).isPositive();
        assertThat(plan.budget().maxEmbeddingCalls()).isPositive();
        assertThat(plan.budget().totalCalls())
                .as("no provider call may happen before the measured run")
                .isZero();
    }

    @Test
    @DisplayName("the live evaluation ingests the full corpus, runs 10 cases and writes reports")
    void liveEvaluationRun() throws Exception {
        LiveEvalPreflight.Plan plan = LiveEvalPreflight.requireReady();
        EvalDataset full = EvalDatasetLoader.load(DATASET);
        EvalDataset selected = LiveEvalCases.select(full, plan.budget().maxCases());

        Instant startedAt = Instant.now();
        try (ConfigurableApplicationContext context = LiveEvalGuardedContext.create(
                plan, VeriDocApplication.class, List.of(EvalTestConfig.class),
                LiveEvaluationTestBase.liveInfrastructureArguments())) {

            EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);
            ChatModel chatModel = context.getBean(ChatModel.class);
            EmbeddingProperties embeddingProperties = context.getBean(EmbeddingProperties.class);
            LiveEvalIngestion.requireLiveEmbeddingModel(embeddingModel, embeddingProperties.dimensions());
            LiveEvalIngestion.requireLiveChatModel(chatModel);

            UserService users = context.getBean(UserService.class);
            User user = users.register("live-eval-" + UUID.randomUUID() + "@example.com",
                    "live-eval-password-not-used-" + UUID.randomUUID(), "Live Eval");
            AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(),
                    user.getRole(), user.getTokenVersion());

            DocumentService documents = context.getBean(DocumentService.class);
            DocumentRepository documentRepository = context.getBean(DocumentRepository.class);
            DocumentChunkRepository chunkRepository = context.getBean(DocumentChunkRepository.class);
            PgVectorChunkRepository vectorRepository = context.getBean(PgVectorChunkRepository.class);
            ChunkingProperties chunkingProperties = context.getBean(ChunkingProperties.class);

            LiveEvalIngestion ingestion = new LiveEvalIngestion(documents, documentRepository,
                    chunkRepository, vectorRepository, plan.budget(),
                    embeddingProperties.model(), embeddingProperties.version(),
                    embeddingProperties.dimensions(), chunkingProperties.minTokens());
            LiveEvalIngestion.Report ingestionReport = ingestion.ingest(principal, selected);

            assertThat(ingestionReport.documents())
                    .as("the full corpus must be ingested, not a subset")
                    .hasSize(LiveEvalCases.EXPECTED_CORPUS_DOCUMENTS);
            assertThat(ingestionReport.documents())
                    .allMatch(document -> "READY".equals(document.status()));
            assertThat(ingestionReport.totalChunks()).isPositive();

            ConversationService conversations = context.getBean(ConversationService.class);
            ChatService chat = context.getBean(ChatService.class);
            RecordingPromptBuilder prompts = context.getBean(RecordingPromptBuilder.class);
            MessageRepository messages = context.getBean(MessageRepository.class);
            RetrievalProperties retrievalProperties = context.getBean(RetrievalProperties.class);
            AiProperties aiProperties = context.getBean(AiProperties.class);

            LiveEvalTransport transport = new SpringLiveEvalTransport(principal, conversations,
                    chat, prompts, messages, ingestionReport.documentIds());
            LiveEvalRunner runner = new LiveEvalRunner(transport, plan.budget(),
                    new EvalConversationPlanner(selected.cases()));
            LiveEvalRunner.Result result = runner.run(selected, retrievalProperties.topK());

            Instant finishedAt = Instant.now();
            LiveEvalReport report = LiveEvalReport.create(UUID.randomUUID(), startedAt, finishedAt,
                    selected, retrievalProperties.topK(), aiProperties.chatModel(),
                    embeddingProperties.model(), embeddingProperties.dimensions(),
                    embeddingProperties.version(), ingestionReport, result, plan.budget());

            List<Path> files = report.write(Path.of("target", "live-eval"));
            assertThat(files).hasSize(2).allMatch(Files::exists);

            assertThat(result.report().results()).hasSize(LiveEvalCases.REQUIRED_CASES);
            assertThat(result.caseRuns()).hasSize(LiveEvalCases.REQUIRED_CASES);
            assertThat(report.aggregates().refusalCorrectness()).isBetween(0.0, 1.0);
            assertThat(report.budget().usedCases()).isEqualTo(LiveEvalCases.REQUIRED_CASES);
            assertThat(report.budget().usedCases()).isLessThanOrEqualTo(report.budget().maxCases());
            assertThat(report.budget().ingestionEmbeddingCalls()).isPositive();

            System.out.println(report.toText());
        }
    }
}
