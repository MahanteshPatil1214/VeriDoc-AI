package com.veridoc.ai.evaluation.live;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.mock.web.MockMultipartFile;

import com.veridoc.ai.document.api.dto.DocumentDtos.DocumentStatusResponse;
import com.veridoc.ai.document.api.dto.DocumentDtos.UploadResponse;
import com.veridoc.ai.document.application.DocumentService;
import com.veridoc.ai.document.domain.DocumentChunk;
import com.veridoc.ai.document.domain.DocumentStatus;
import com.veridoc.ai.document.persistence.DocumentChunkRepository;
import com.veridoc.ai.document.persistence.DocumentRepository;
import com.veridoc.ai.evaluation.EvalDataset;
import com.veridoc.ai.evaluation.EvalDatasetValidator;
import com.veridoc.ai.infrastructure.vector.PgVectorChunkRepository;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;
import com.veridoc.ai.support.TestPdfs;

/**
 * Re-ingests the full evaluation corpus through the production upload path.
 *
 * <p>The live run owns a throwaway database and a brand-new user, so it cannot
 * delete or overwrite another user's documents. Duplicate ingestion is refused
 * before the first upload: the corpus must be the shipped 13 documents with
 * unique filenames, and the evaluation user must own no non-deleted documents.
 * The production upload path still performs its own content-hash duplicate
 * check.
 *
 * <p>After a document reaches {@code READY}, every chunk is checked against the
 * configured embedding model, version and width. A stub embedding model or a
 * vector from an incompatible embedding space fails the run instead of being
 * silently retrieved.
 */
public final class LiveEvalIngestion {

    private static final Duration PER_DOCUMENT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final DocumentService documents;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunks;
    private final PgVectorChunkRepository vectors;
    private final LiveEvalBudget budget;
    private final String expectedModel;
    private final int expectedVersion;
    private final int expectedDimensions;
    private final int minTokensPerPage;

    public LiveEvalIngestion(DocumentService documents,
                             DocumentRepository documentRepository,
                             DocumentChunkRepository chunks,
                             PgVectorChunkRepository vectors,
                             LiveEvalBudget budget,
                             String expectedModel,
                             int expectedVersion,
                             int expectedDimensions,
                             int minTokensPerPage) {
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.chunks = chunks;
        this.vectors = vectors;
        this.budget = budget;
        this.expectedModel = expectedModel;
        this.expectedVersion = expectedVersion;
        this.expectedDimensions = expectedDimensions;
        this.minTokensPerPage = minTokensPerPage;
    }

    public Report ingest(AuthenticatedUser owner, EvalDataset dataset) {
        EvalDatasetValidator.validate(dataset, minTokensPerPage);
        requireUniqueCorpus(dataset);
        if (documentRepository.countByOwnerIdAndStatusNot(owner.userId(), DocumentStatus.DELETED) != 0) {
            throw new LiveEvalIngestionException(
                    "evaluation user already owns documents; refusing duplicate ingestion");
        }

        List<IngestedDocument> ingested = new ArrayList<>();
        List<UUID> documentIds = new ArrayList<>();
        Map<String, UUID> byFilename = new LinkedHashMap<>();
        int totalChunks = 0;

        for (EvalDataset.CorpusDocument corpusDocument : dataset.corpus()) {
            budget.assertWithinDeadline();
            UUID documentId;
            try {
                byte[] pdf = TestPdfs.pages(corpusDocument.pageTexts().toArray(String[]::new));
                MockMultipartFile file = new MockMultipartFile("file", corpusDocument.filename(),
                        "application/pdf", pdf);
                UploadResponse uploaded = documents.upload(owner, file);
                documentId = uploaded.id();
            } catch (IOException e) {
                throw new LiveEvalIngestionException(
                        "failed to build/upload corpus document " + corpusDocument.filename(), e);
            } catch (RuntimeException e) {
                throw new LiveEvalIngestionException(
                        "production upload rejected corpus document " + corpusDocument.filename()
                                + ": " + e.getClass().getSimpleName(), e);
            }

            DocumentStatusResponse status = awaitReady(owner, documentId, corpusDocument.filename());
            int chunkCount = verifyEmbeddings(documentId, corpusDocument.filename(), status);
            int pages = status.pageCount() == null ? corpusDocument.pageTexts().size() : status.pageCount();
            totalChunks += chunkCount;
            documentIds.add(documentId);
            byFilename.put(corpusDocument.filename(), documentId);
            ingested.add(new IngestedDocument(corpusDocument.filename(), status.status(), pages,
                    chunkCount, expectedModel, expectedVersion, expectedDimensions));
        }

        if (documentIds.size() != dataset.corpus().size()) {
            throw new LiveEvalIngestionException("ingestion did not cover the full corpus");
        }
        return new Report(List.copyOf(ingested), totalChunks, budget.embeddingCalls(),
                List.copyOf(documentIds), Map.copyOf(byFilename));
    }

    private DocumentStatusResponse awaitReady(AuthenticatedUser owner, UUID documentId, String filename) {
        long stop = Math.min(System.nanoTime() + PER_DOCUMENT_TIMEOUT.toNanos(),
                System.nanoTime() + budget.remainingTime().toNanos());
        while (System.nanoTime() < stop) {
            budget.assertWithinDeadline();
            DocumentStatusResponse status = documents.status(owner, documentId);
            if (DocumentStatus.READY.name().equals(status.status())) {
                return status;
            }
            if (DocumentStatus.FAILED.name().equals(status.status())) {
                throw new LiveEvalIngestionException("corpus document " + filename
                        + " failed during stage " + safeStage(status.stage()));
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LiveEvalIngestionException("interrupted while waiting for " + filename, e);
            }
        }
        throw new LiveEvalIngestionException("corpus document " + filename
                + " did not reach READY within " + PER_DOCUMENT_TIMEOUT);
    }

    private int verifyEmbeddings(UUID documentId, String filename, DocumentStatusResponse status) {
        List<DocumentChunk> stored = chunks.findByDocumentIdOrderByChunkIndexAsc(documentId);
        if (stored.isEmpty()) {
            throw new LiveEvalIngestionException("corpus document " + filename
                    + " reached READY with no chunks");
        }
        if (status.chunkCount() != null && status.chunkCount() != stored.size()) {
            throw new LiveEvalIngestionException("corpus document " + filename
                    + " reported " + status.chunkCount() + " chunks but stored " + stored.size());
        }

        Set<UUID> versions = new HashSet<>();
        for (DocumentChunk chunk : stored) {
            if (!Objects.equals(expectedModel, chunk.getEmbeddingModel())) {
                throw new LiveEvalIngestionException("corpus document " + filename
                        + " has an incompatible embedding model");
            }
            if (!Objects.equals(expectedVersion, chunk.getEmbeddingVersion())) {
                throw new LiveEvalIngestionException("corpus document " + filename
                        + " has an incompatible embedding version");
            }
            versions.add(chunk.getVersionId());
        }
        for (UUID versionId : versions) {
            Integer width = vectors.storedDimensions(versionId);
            if (width == null || width != expectedDimensions) {
                throw new LiveEvalIngestionException("corpus document " + filename
                        + " has an incompatible embedding width");
            }
        }
        return stored.size();
    }

    /**
     * Refuses a corpus that is not the shipped 13 documents or that repeats a
     * filename. Separated from Spring so the rule is unit-tested.
     */
    public static void requireUniqueCorpus(EvalDataset dataset) {
        if (dataset == null || dataset.corpus() == null
                || dataset.corpus().size() != LiveEvalCases.EXPECTED_CORPUS_DOCUMENTS) {
            throw new LiveEvalIngestionException("live evaluation requires the full "
                    + LiveEvalCases.EXPECTED_CORPUS_DOCUMENTS + "-document corpus");
        }
        Set<String> names = new HashSet<>();
        for (EvalDataset.CorpusDocument document : dataset.corpus()) {
            if (document.filename() == null || document.filename().isBlank()) {
                throw new LiveEvalIngestionException("corpus document has no filename");
            }
            if (!names.add(document.filename())) {
                throw new LiveEvalIngestionException(
                        "duplicate corpus document filename: " + document.filename());
            }
        }
    }

    /**
     * Rejects a model that is not the budgeted live embedding model, is a known
     * deterministic stub, or reports a different width.
     */
    public static void requireLiveEmbeddingModel(EmbeddingModel model, int expectedDimensions) {
        if (!(model instanceof BudgetedEmbeddingModel budgeted)) {
            throw new LiveEvalIngestionException(
                    "live evaluation requires the budgeted embedding model; stub fallback refused");
        }
        EmbeddingModel delegate = budgeted.delegate();
        if (delegate instanceof BudgetedEmbeddingModel) {
            throw new LiveEvalIngestionException(
                    "live evaluation refused a doubly wrapped embedding model");
        }
        String name = delegate.getClass().getName();
        if (name.startsWith("com.veridoc.ai.support.") || name.contains("Deterministic")) {
            throw new LiveEvalIngestionException(
                    "live evaluation refused a stub embedding model");
        }
        int actualDimensions = budgeted.dimensions();
        if (actualDimensions != expectedDimensions) {
            throw new LiveEvalIngestionException(
                    "live embedding model reports width " + actualDimensions
                            + " but the store expects " + expectedDimensions);
        }
    }

    /** Rejects a chat model that is not the budgeted live model. */
    public static void requireLiveChatModel(ChatModel model) {
        if (!(model instanceof BudgetedChatModel budgeted)) {
            throw new LiveEvalIngestionException(
                    "live evaluation requires the budgeted chat model; stub fallback refused");
        }
        ChatModel delegate = budgeted.delegate();
        if (delegate instanceof BudgetedChatModel) {
            throw new LiveEvalIngestionException(
                    "live evaluation refused a doubly wrapped chat model");
        }
        String name = delegate.getClass().getName();
        if (name.startsWith("com.veridoc.ai.support.") || name.contains("Deterministic")) {
            throw new LiveEvalIngestionException("live evaluation refused a stub chat model");
        }
    }

    private static String safeStage(String stage) {
        return stage == null || stage.isBlank() ? "unknown" : stage;
    }

    public record IngestedDocument(
            String filename,
            String status,
            Integer pages,
            Integer chunks,
            String embeddingModel,
            Integer embeddingVersion,
            Integer dimensions
    ) {
    }

    public record Report(
            List<IngestedDocument> documents,
            int totalChunks,
            int embeddingCalls,
            List<UUID> documentIds,
            Map<String, UUID> documentIdsByFilename
    ) {
        public Report {
            documents = List.copyOf(documents);
            documentIds = List.copyOf(documentIds);
            documentIdsByFilename = Map.copyOf(documentIdsByFilename);
        }
    }
}
