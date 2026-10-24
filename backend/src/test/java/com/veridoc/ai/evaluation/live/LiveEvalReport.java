package com.veridoc.ai.evaluation.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalCaseResult;
import com.veridoc.ai.evaluation.EvalConversationPlanner;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalReport;
import com.veridoc.ai.evaluation.EvidencePassage;

import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted result of one live run.
 *
 * <p>This is a deliberately narrow view of {@link EvalReport}: it carries only
 * identifiers, labels, scores, timings and token counts. Questions, answers,
 * system prompts, retrieved text, provider responses and credentials are never
 * copied into it, so the JSON and text files under {@code target/live-eval/}
 * are safe to attach to a review.
 *
 * <p>Retrieval and citation scores are provenance checks. The report states
 * that explicitly and does not claim semantic faithfulness.
 */
public record LiveEvalReport(
        Run run,
        Ingestion ingestion,
        Budget budget,
        Aggregates aggregates,
        List<CaseOutcome> cases,
        String notes
) {

    public static final String JSON_FILE = "live-eval-report.json";
    public static final String TEXT_FILE = "live-eval-report.txt";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static final String NOTES =
            "Retrieval and citation metrics are provenance/structural checks only. "
                    + "This run does not measure semantic faithfulness or verify answer claims. "
                    + "No question, answer, system prompt, retrieved text or credential is stored.";

    public LiveEvalReport {
        cases = List.copyOf(cases);
    }

    public static LiveEvalReport create(UUID runId, Instant startedAt, Instant finishedAt,
                                        EvalDataset dataset, int retrievalK,
                                        String chatModel, String embeddingModel,
                                        int embeddingDimensions, int embeddingVersion,
                                        LiveEvalIngestion.Report ingestion,
                                        LiveEvalRunner.Result result,
                                        LiveEvalBudget budget) {
        EvalReport report = result.report();
        EvalConversationPlanner planner = new EvalConversationPlanner(dataset.cases());

        List<CaseOutcome> outcomes = new ArrayList<>(report.results().size());
        for (EvalCaseResult caseResult : report.results()) {
            EvalCase evalCase = caseResult.evalCase();
            LiveEvalRunner.LiveCaseRun timing = result.caseRuns().get(evalCase.id());
            if (timing == null) {
                throw new IllegalStateException("missing measurements for case " + evalCase.id());
            }
            int expectedHistory = planner.planFor(evalCase.id()).expectedHistoryMessages();
            outcomes.add(new CaseOutcome(
                    evalCase.id(),
                    evalCase.category(),
                    evalCase.resolvedDocuments(),
                    evalCase.expectedPages(),
                    evalCase.expectRefusal(),
                    caseResult.observation().refusal(),
                    caseResult.refusalCorrect(),
                    caseResult.hit(),
                    caseResult.reciprocalRank(),
                    caseResult.precisionAtK(),
                    caseResult.recallAtK(),
                    caseResult.keywordCoverage(),
                    caseResult.citationPrecision(),
                    caseResult.pageCitationPrecision(),
                    caseResult.pageCitationRecall(),
                    refs(caseResult.observation().retrieved()),
                    refs(caseResult.observation().citations()),
                    timing.caseLatencyMs(),
                    timing.finalTurnLatencyMs(),
                    timing.modelLatencyMs(),
                    timing.promptTokens(),
                    timing.completionTokens(),
                    timing.totalTokens(),
                    expectedHistory,
                    timing.historyMessages()));
        }

        long totalCaseLatency = 0L;
        long maxCaseLatency = 0L;
        int usageCases = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        for (LiveEvalRunner.LiveCaseRun run : outcomes.stream()
                .map(o -> result.caseRuns().get(o.id())).toList()) {
            totalCaseLatency += run.caseLatencyMs();
            maxCaseLatency = Math.max(maxCaseLatency, run.caseLatencyMs());
            if (run.totalTokens() != null && run.totalTokens() > 0) {
                usageCases++;
            }
            if (run.promptTokens() != null) {
                promptTokens += run.promptTokens();
            }
            if (run.completionTokens() != null) {
                completionTokens += run.completionTokens();
            }
            if (run.totalTokens() != null) {
                totalTokens += run.totalTokens();
            }
        }
        long meanCaseLatency = outcomes.isEmpty() ? 0L : totalCaseLatency / outcomes.size();

        Aggregates aggregates = new Aggregates(
                report.hitRate(),
                report.meanReciprocalRank(),
                report.meanPrecisionAtK(),
                report.meanRecallAtK(),
                report.meanKeywordCoverage(),
                report.meanCitationPrecision(),
                report.meanPageCitationPrecision(),
                report.meanPageCitationRecall(),
                report.refusalCorrectness(),
                report.pageApplicableCaseCount(),
                report.missingPageCitationCount(),
                usageCases,
                promptTokens,
                completionTokens,
                totalTokens,
                meanCaseLatency,
                maxCaseLatency,
                totalCaseLatency);

        int usedEmbeddings = budget.embeddingCalls();
        Budget budgetReport = new Budget(
                budget.maxCases(),
                budget.maxChatCalls(),
                budget.maxEmbeddingCalls(),
                budget.maxDuration().toString(),
                budget.cases(),
                budget.chatCalls(),
                usedEmbeddings,
                ingestion.embeddingCalls(),
                Math.max(0, usedEmbeddings - ingestion.embeddingCalls()),
                budget.remainingTime().toSeconds());

        Ingestion ingestionReport = new Ingestion(
                ingestion.documents(),
                ingestion.documents().size(),
                ingestion.totalChunks(),
                ingestion.embeddingCalls());

        Run run = new Run(
                runId.toString(),
                startedAt.toString(),
                finishedAt.toString(),
                Duration.between(startedAt, finishedAt).toMillis(),
                dataset.name(),
                dataset.corpus().size(),
                dataset.cases().size(),
                retrievalK,
                chatModel,
                embeddingModel,
                embeddingDimensions,
                embeddingVersion);

        return new LiveEvalReport(run, ingestionReport, budgetReport, aggregates, outcomes, NOTES);
    }

    private static List<EvidenceRef> refs(List<EvidencePassage> passages) {
        List<EvidenceRef> refs = new ArrayList<>(passages.size());
        for (EvidencePassage passage : passages) {
            refs.add(new EvidenceRef(passage.documentFilename(),
                    passage.pageNumber(),
                    passage.chunkId() == null ? null : passage.chunkId().toString()));
        }
        return List.copyOf(refs);
    }

    public String toJson() {
        try {
            return JSON.writer().withDefaultPrettyPrinter().writeValueAsString(this);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to serialize live evaluation report", e);
        }
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("VeriDoc live RAG evaluation\n");
        sb.append(String.format(Locale.ROOT,
                "run=%s dataset=%s cases=%d corpus=%d k=%d%n",
                run.runId(), run.datasetName(), run.cases(), run.corpusDocuments(), run.retrievalK()));
        sb.append(String.format(Locale.ROOT,
                "started=%s finished=%s durationMs=%d%n",
                run.startedAt(), run.finishedAt(), run.durationMs()));
        sb.append(String.format(Locale.ROOT,
                "models: chat=%s embedding=%s dimensions=%d version=%d%n",
                run.chatModel(), run.embeddingModel(), run.embeddingDimensions(),
                run.embeddingVersion()));
        sb.append(String.format(Locale.ROOT,
                "ingestion: documents=%d chunks=%d embeddingCalls=%d%n",
                ingestion.totalDocuments(), ingestion.totalChunks(), ingestion.embeddingCalls()));
        sb.append(String.format(Locale.ROOT,
                "budget: cases=%d/%d chat=%d/%d embedding=%d/%d maxDuration=%s remainingSeconds=%d%n",
                budget.usedCases(), budget.maxCases(),
                budget.usedChatCalls(), budget.maxChatCalls(),
                budget.usedEmbeddingCalls(), budget.maxEmbeddingCalls(),
                budget.maxDuration(), budget.remainingSeconds()));
        sb.append(String.format(Locale.ROOT,
                "budgetDetail: ingestionEmbeddingCalls=%d caseEmbeddingCalls=%d%n",
                budget.ingestionEmbeddingCalls(), budget.caseEmbeddingCalls()));
        sb.append(String.format(Locale.ROOT,
                "metrics: hitRate=%.4f MRR=%.4f precisionAtK=%.4f recallAtK=%.4f "
                        + "keywordCoverage=%.4f citationPrecision=%.4f refusalCorrectness=%.4f%n",
                aggregates.hitRate(), aggregates.meanReciprocalRank(),
                aggregates.meanPrecisionAtK(), aggregates.meanRecallAtK(),
                aggregates.meanKeywordCoverage(), aggregates.meanCitationPrecision(),
                aggregates.refusalCorrectness()));
        sb.append(String.format(Locale.ROOT,
                "pageCitations: precision=%.4f recall=%.4f applicableCases=%d "
                        + "missingPageCitations=%d%n",
                aggregates.meanPageCitationPrecision(), aggregates.meanPageCitationRecall(),
                aggregates.pageApplicableCaseCount(), aggregates.missingPageCitationCount()));
        sb.append(String.format(Locale.ROOT,
                "tokens: casesWithUsage=%d prompt=%d completion=%d total=%d%n",
                aggregates.casesWithTokenUsage(), aggregates.promptTokens(),
                aggregates.completionTokens(), aggregates.totalTokens()));
        sb.append(String.format(Locale.ROOT,
                "latencyMs: meanCase=%d maxCase=%d totalCase=%d%n",
                aggregates.meanCaseLatencyMs(), aggregates.maxCaseLatencyMs(),
                aggregates.totalCaseLatencyMs()));
        sb.append("per-case:\n");
        for (CaseOutcome c : cases) {
            sb.append(String.format(Locale.ROOT,
                    "  [%s/%s] expected=%s pages=%s expectRefusal=%s observedRefusal=%s "
                            + "refusalCorrect=%s hit=%s RR=%.4f P@k=%.4f R@k=%.4f "
                            + "keywords=%.4f citationPrecision=%.4f "
                            + "pagePrecision=%.4f pageRecall=%.4f%n",
                    c.id(), c.category(),
                    c.expectedDocuments().isEmpty() ? "-" : String.join("|", c.expectedDocuments()),
                    c.expectedPages().isEmpty() ? "-" : c.expectedPages().toString(),
                    c.expectRefusal(), c.observedRefusal(), c.refusalCorrect(), c.hit(),
                    c.reciprocalRank(), c.precisionAtK(), c.recallAtK(), c.keywordCoverage(),
                    c.citationPrecision(), c.pageCitationPrecision(), c.pageCitationRecall()));
            sb.append(String.format(Locale.ROOT,
                    "    retrieved=%s%n", refsText(c.retrieved())));
            sb.append(String.format(Locale.ROOT,
                    "    cited=%s%n", refsText(c.cited())));
            sb.append(String.format(Locale.ROOT,
                    "    latencyMs: case=%d finalTurn=%d model=%d "
                            + "tokens: prompt=%s completion=%s total=%s history=%d/%d%n",
                    c.caseLatencyMs(), c.finalTurnLatencyMs(), c.modelLatencyMs(),
                    c.promptTokens(), c.completionTokens(), c.totalTokens(),
                    c.observedHistoryMessages(), c.expectedHistoryMessages()));
        }
        sb.append("notes: ").append(notes).append('\n');
        return sb.toString();
    }

    private static String refsText(List<EvidenceRef> refs) {
        if (refs.isEmpty()) {
            return "-";
        }
        return refs.stream()
                .map(r -> r.documentFilename() + (r.pageNumber() == null ? "" : "#" + r.pageNumber()))
                .toList()
                .toString();
    }

    public List<Path> write(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must be provided");
        Files.createDirectories(directory);
        Path json = directory.resolve(JSON_FILE);
        Path text = directory.resolve(TEXT_FILE);
        Files.writeString(json, toJson(), StandardCharsets.UTF_8);
        Files.writeString(text, toText(), StandardCharsets.UTF_8);
        return List.of(json, text);
    }

    public record Run(
            String runId,
            String startedAt,
            String finishedAt,
            long durationMs,
            String datasetName,
            int corpusDocuments,
            int cases,
            int retrievalK,
            String chatModel,
            String embeddingModel,
            int embeddingDimensions,
            int embeddingVersion
    ) {
    }

    public record Ingestion(
            List<LiveEvalIngestion.IngestedDocument> documents,
            int totalDocuments,
            int totalChunks,
            int embeddingCalls
    ) {
        public Ingestion {
            documents = List.copyOf(documents);
        }
    }

    public record Budget(
            int maxCases,
            int maxChatCalls,
            int maxEmbeddingCalls,
            String maxDuration,
            int usedCases,
            int usedChatCalls,
            int usedEmbeddingCalls,
            int ingestionEmbeddingCalls,
            int caseEmbeddingCalls,
            long remainingSeconds
    ) {
    }

    public record Aggregates(
            double hitRate,
            double meanReciprocalRank,
            double meanPrecisionAtK,
            double meanRecallAtK,
            double meanKeywordCoverage,
            double meanCitationPrecision,
            double meanPageCitationPrecision,
            double meanPageCitationRecall,
            double refusalCorrectness,
            int pageApplicableCaseCount,
            long missingPageCitationCount,
            int casesWithTokenUsage,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long meanCaseLatencyMs,
            long maxCaseLatencyMs,
            long totalCaseLatencyMs
    ) {
    }

    public record CaseOutcome(
            String id,
            String category,
            List<String> expectedDocuments,
            List<Integer> expectedPages,
            boolean expectRefusal,
            boolean observedRefusal,
            boolean refusalCorrect,
            boolean hit,
            double reciprocalRank,
            double precisionAtK,
            double recallAtK,
            double keywordCoverage,
            double citationPrecision,
            double pageCitationPrecision,
            double pageCitationRecall,
            List<EvidenceRef> retrieved,
            List<EvidenceRef> cited,
            long caseLatencyMs,
            long finalTurnLatencyMs,
            long modelLatencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            int expectedHistoryMessages,
            int observedHistoryMessages
    ) {
        public CaseOutcome {
            expectedDocuments = List.copyOf(expectedDocuments);
            expectedPages = List.copyOf(expectedPages);
            retrieved = List.copyOf(retrieved);
            cited = List.copyOf(cited);
        }
    }

    public record EvidenceRef(String documentFilename, Integer pageNumber, String chunkId) {
    }
}
