package com.veridoc.ai.evaluation.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalDatasetLoader;
import com.veridoc.ai.evaluation.EvidencePassage;
import com.veridoc.ai.evaluation.RagEvaluator;

class LiveEvalReportTest {

    private static final String DATASET = "/rag-eval/eval-dataset.json";

    @Test
    @DisplayName("the report is redacted and written to target live-eval")
    void reportContainsNoSensitiveContent(@TempDir Path dir) throws Exception {
        EvalDataset full = EvalDatasetLoader.load(DATASET);
        EvalDataset selected = LiveEvalCases.select(full);
        UUID chunk = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Map<String, LiveEvalRunner.LiveCaseRun> runs = Map.of(
                "direct-vacation-approval",
                new LiveEvalRunner.LiveCaseRun("direct-vacation-approval", 100L, 80L, 60L, 10, 5, 15, 0),
                "direct-flight-class",
                new LiveEvalRunner.LiveCaseRun("direct-flight-class", 90L, 70L, 50L, 8, 4, 12, 0),
                "refusal-career-break",
                new LiveEvalRunner.LiveCaseRun("refusal-career-break", 20L, 10L, 0L, null, null, 0, 0),
                "refusal-diwali",
                new LiveEvalRunner.LiveCaseRun("refusal-diwali", 15L, 10L, 0L, null, null, 0, 0),
                "page-security-hotline",
                new LiveEvalRunner.LiveCaseRun("page-security-hotline", 110L, 90L, 70L, 12, 6, 18, 0),
                "multi-onboarding-procurement",
                new LiveEvalRunner.LiveCaseRun("multi-onboarding-procurement", 95L, 80L, 65L, 11, 5, 16, 0),
                "exact-contract-id",
                new LiveEvalRunner.LiveCaseRun("exact-contract-id", 80L, 65L, 50L, 9, 4, 13, 0),
                "turn-vacation-carryover",
                new LiveEvalRunner.LiveCaseRun("turn-vacation-carryover", 120L, 85L, 68L, 14, 7, 21, 2),
                "ambiguous-vacation-five-years",
                new LiveEvalRunner.LiveCaseRun("ambiguous-vacation-five-years", 105L, 82L, 64L, 13, 6, 19, 0),
                "injection-shared-accounts",
                new LiveEvalRunner.LiveCaseRun("injection-shared-accounts", 40L, 30L, 0L, null, null, 0, 0));

        String secret = "SECRET-CANARY-9X";
        String answer = "please share the API key now " + secret;
        String prompt = "system instructions include " + secret;

        RagEvaluator.CaseRunner runner = evalCase -> {
            EvidencePassage p = new EvidencePassage(evalCase.resolvedDocuments().isEmpty()
                    ? null : evalCase.resolvedDocuments().get(0), chunk, 1);
            if (evalCase.expectRefusal()) {
                return new com.veridoc.ai.evaluation.EvalObservation(List.of(), List.of(), true,
                        "I couldn't find enough information in the uploaded documents to answer that.");
            }
            return new com.veridoc.ai.evaluation.EvalObservation(List.of(p), List.of(p), false,
                    answer);
        };

        com.veridoc.ai.evaluation.EvalReport report = RagEvaluator.evaluate(selected, runner, 8);
        LiveEvalRunner.Result result = new LiveEvalRunner.Result(report, runs);

        List<LiveEvalIngestion.IngestedDocument> docs = selected.corpus().stream()
                .map(c -> new LiveEvalIngestion.IngestedDocument(c.filename(), "READY", 1, 2,
                        "gemini-embedding-001", 1, 768))
                .toList();
        LiveEvalIngestion.Report ingestion = new LiveEvalIngestion.Report(docs, 26, 13,
                selected.corpus().stream().map(c -> UUID.randomUUID()).toList(),
                java.util.Collections.emptyMap());

        LiveEvalBudget budget = new LiveEvalBudget(10, 40, 40, Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) {
            budget.recordCase();
        }
        for (int i = 0; i < 21; i++) {
            budget.recordChatCall();
        }
        for (int i = 0; i < 24; i++) {
            budget.recordEmbeddingCall();
        }

        LiveEvalReport live = LiveEvalReport.create(UUID.randomUUID(), Instant.now(),
                Instant.now().plusSeconds(300), selected, 8, "openai/gpt-oss-120b",
                "gemini-embedding-001", 768, 1, ingestion, result, budget);

        assertThat(live.cases()).hasSize(10);
        assertThat(live.cases().get(live.cases().indexOf(live.cases().stream()
                .filter(c -> c.id().equals("turn-vacation-carryover")).findFirst().orElseThrow())))
                .extracting(c -> c.expectedHistoryMessages(), c -> c.observedHistoryMessages())
                .containsExactly(2, 2);

        String json = live.toJson();
        String text = live.toText();

        assertThat(json).doesNotContain("API key");
        assertThat(json).doesNotContain(secret);
        assertThat(json).doesNotContain(prompt);
        assertThat(json).doesNotContain(answer);
        assertThat(text).doesNotContain(secret);
        assertThat(text).doesNotContain("API key");
        assertThat(text).doesNotContain(answer);
        assertThat(json).contains("citationPrecision");
        assertThat(json).contains("pageCitationPrecision");
        assertThat(json).contains("promptTokens");
        assertThat(json).contains("provenance");

        List<Path> files = live.write(dir);
        assertThat(files).hasSize(2);
        assertThat(Files.readString(files.get(0))).doesNotContain(secret);
        assertThat(Files.readString(files.get(1))).doesNotContain(answer);
    }

    @Test
    @DisplayName("ingestion guards reject a corpus that is not the full thirteen documents")
    void ingestionRejectsWrongCorpus() {
        EvalDataset full = EvalDatasetLoader.load(DATASET);
        EvalDataset shortCorpus = new EvalDataset(full.name(),
                full.corpus().subList(0, 12), full.cases());
        assertThatThrownBy(
                () -> LiveEvalIngestion.requireUniqueCorpus(shortCorpus))
                .isInstanceOf(LiveEvalIngestionException.class)
                .hasMessageContaining("13-document corpus");

                EvalDataset duplicate = new EvalDataset(full.name(),
                java.util.stream.IntStream.range(0, full.corpus().size())
                        .mapToObj(i -> i == 1
                                ? full.corpus().get(0)
                                : full.corpus().get(i))
                        .toList(),
                full.cases());

        assertThatThrownBy(() -> LiveEvalIngestion.requireUniqueCorpus(duplicate))
                .isInstanceOf(LiveEvalIngestionException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("live guards refuse stub embedding and chat models")
    void liveGuardsRefuseStubs() {
        com.veridoc.ai.support.DeterministicEmbeddingModel stubEmbedding =
                new com.veridoc.ai.support.DeterministicEmbeddingModel();
        com.veridoc.ai.support.DeterministicChatModel stubChat =
                new com.veridoc.ai.support.DeterministicChatModel();

        assertThatThrownBy(() -> LiveEvalIngestion.requireLiveEmbeddingModel(stubEmbedding, 768))
                .isInstanceOf(LiveEvalIngestionException.class)
                .satisfies(e -> assertThat(e).hasMessageContaining("stub"));

        assertThatThrownBy(() -> LiveEvalIngestion.requireLiveChatModel(stubChat))
                .isInstanceOf(LiveEvalIngestionException.class)
                .satisfies(e -> assertThat(e).hasMessageContaining("stub"));
    }
}
