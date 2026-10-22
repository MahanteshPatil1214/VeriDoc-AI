package com.veridoc.ai.document.ingestion.processing;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.document.domain.Document;
import com.veridoc.ai.document.domain.DocumentChunk;
import com.veridoc.ai.document.domain.DocumentStatus;
import com.veridoc.ai.document.domain.DocumentVersion;
import com.veridoc.ai.document.ingestion.chunking.TokenChunker;
import com.veridoc.ai.document.ingestion.pdf.PdfExtractor;
import com.veridoc.ai.document.persistence.DocumentChunkRepository;
import com.veridoc.ai.document.persistence.DocumentRepository;
import com.veridoc.ai.document.persistence.DocumentVersionRepository;
import com.veridoc.ai.document.storage.LocalDocumentStorage;
import com.veridoc.ai.infrastructure.vector.PgVectorChunkRepository;

/**
 * Asynchronous document processing pipeline:
 * EXTRACTING -> CHUNKING -> EMBEDDING -> INDEXING -> READY.
 *
 * <p>On failure the document is marked FAILED with a truncated, non-sensitive
 * message. The stored original is deliberately kept so the document can be
 * re-processed once the cause is fixed.
 */
@Service
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final PgVectorChunkRepository pgVectorChunkRepository;
    private final LocalDocumentStorage storage;
    private final PdfExtractor pdfExtractor;
    private final TokenChunker tokenChunker;
    private final EmbeddingBatcher embeddingBatcher;
    private final EmbeddingProperties embeddingProperties;

    public DocumentProcessor(DocumentRepository documentRepository,
                             DocumentVersionRepository documentVersionRepository,
                             DocumentChunkRepository documentChunkRepository,
                             PgVectorChunkRepository pgVectorChunkRepository,
                             LocalDocumentStorage storage,
                             PdfExtractor pdfExtractor,
                             TokenChunker tokenChunker,
                             EmbeddingBatcher embeddingBatcher,
                             EmbeddingProperties embeddingProperties) {
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.pgVectorChunkRepository = pgVectorChunkRepository;
        this.storage = storage;
        this.pdfExtractor = pdfExtractor;
        this.tokenChunker = tokenChunker;
        this.embeddingBatcher = embeddingBatcher;
        this.embeddingProperties = embeddingProperties;
    }

    @Async("taskExecutor")
    public void process(UUID documentId) {
        try {
            Document document = load(documentId);
            document.requireNotDeleted();

            document.markProcessing(DocumentStatus.EXTRACTING);
            documentRepository.save(document);

            byte[] pdf = storage.read(document.getStoragePath());
            List<PdfExtractor.PageText> pages = pdfExtractor.extract(pdf);

            document = load(documentId);
            document.markProcessing(DocumentStatus.CHUNKING);
            documentRepository.save(document);
            var chunks = tokenChunker.chunk(pages);
            if (chunks.isEmpty()) {
                throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED,
                        "No extractable text found in the PDF");
            }

            DocumentVersion version = DocumentVersion.create(UUID.randomUUID(), document.getId(),
                    document.getVersion(), document.getSha256(), embeddingProperties.model(),
                    embeddingProperties.version());
            version.setChunkCount(chunks.size());
            documentVersionRepository.save(version);

            document = load(documentId);
            document.markProcessing(DocumentStatus.EMBEDDING);
            documentRepository.save(document);

            List<String> texts = chunks.stream().map(TokenChunker.Chunk::content).toList();
            List<float[]> chunkVectors = embeddingBatcher.embedAll(texts);

            document = load(documentId);
            document.markProcessing(DocumentStatus.INDEXING);
            documentRepository.save(document);

            List<DocumentChunk> storedChunks = new java.util.ArrayList<>(chunks.size());
            List<double[]> vectors = new java.util.ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                var c = chunks.get(i);
                float[] vector = chunkVectors.get(i);
                DocumentChunk dc = DocumentChunk.create(UUID.randomUUID(), document.getId(),
                        version.getId(), document.getOwnerId(), c.chunkIndex(), c.content(),
                        c.pageNumber(), null, c.tokenCount(), c.contentHash(), embeddingProperties.model(),
                        embeddingProperties.version());
                storedChunks.add(dc);
                double[] dbl = new double[vector.length];
                for (int j = 0; j < vector.length; j++) {
                    dbl[j] = vector[j];
                }
                vectors.add(dbl);
            }
            pgVectorChunkRepository.insertWithEmbeddings(storedChunks, vectors);

            document = load(documentId);
            document.markReady(pages.size(), chunks.size());
            documentRepository.save(document);
            log.info("Document {} processed: {} pages, {} chunks, {} embedding batches",
                    document.getId(), pages.size(), chunks.size(),
                    (chunks.size() + embeddingProperties.batchSize() - 1)
                            / embeddingProperties.batchSize());
        } catch (AppException e) {
            fail(documentId, e.getMessage());
        } catch (IOException e) {
            fail(documentId, "Failed to read stored PDF: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error processing document {}", documentId, e);
            fail(documentId, "Unexpected processing error");
        }
    }

    /**
     * Loads a fresh entity for a state mutation. The processing pipeline uses
     * short, per-step transactions (embedding calls can take a long time), so a
     * previously loaded instance is detached by the time it is saved again;
     * re-saving it would fail optimistic locking. Reloading keeps each save a
     * single, valid version bump.
     */
    private Document load(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document not found"));
    }

    private void fail(UUID documentId, String message) {
        try {
            Document document = load(documentId);
            document.markFailed(message);
            documentRepository.save(document);
        } catch (Exception ex) {
            log.error("Failed to persist failure state for document {}", documentId, ex);
        }
    }
}