package com.veridoc.ai.document.ingestion.processing;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
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
import com.veridoc.ai.infrastructure.config.AiConfig.EmbeddingDimensions;
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
    private final EmbeddingModel embeddingModel;
    private final EmbeddingDimensions embeddingDimensions;
    private final EmbeddingProperties embeddingProperties;

    public DocumentProcessor(DocumentRepository documentRepository,
                             DocumentVersionRepository documentVersionRepository,
                             DocumentChunkRepository documentChunkRepository,
                             PgVectorChunkRepository pgVectorChunkRepository,
                             LocalDocumentStorage storage,
                             PdfExtractor pdfExtractor,
                             TokenChunker tokenChunker,
                             EmbeddingModel embeddingModel,
                             EmbeddingDimensions embeddingDimensions,
                             EmbeddingProperties embeddingProperties) {
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.pgVectorChunkRepository = pgVectorChunkRepository;
        this.storage = storage;
        this.pdfExtractor = pdfExtractor;
        this.tokenChunker = tokenChunker;
        this.embeddingModel = embeddingModel;
        this.embeddingDimensions = embeddingDimensions;
        this.embeddingProperties = embeddingProperties;
    }

    @Async("taskExecutor")
    public void process(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document not found"));
        try {
            document.requireNotDeleted();

            document.markProcessing(DocumentStatus.EXTRACTING);
            documentRepository.save(document);

            byte[] pdf = storage.read(document.getStoragePath());
            List<PdfExtractor.PageText> pages = pdfExtractor.extract(pdf);

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

            document.markProcessing(DocumentStatus.EMBEDDING);
            documentRepository.save(document);

            String[] texts = chunks.stream().map(TokenChunker.Chunk::content).toArray(String[]::new);
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(Arrays.asList(texts),
                    EmbeddingOptions.builder().model(embeddingProperties.model()).build()));
            var embeddings = response.getResults();
            if (embeddings.size() != chunks.size()) {
                throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED, "Embedding count mismatch");
            }

            document.markProcessing(DocumentStatus.INDEXING);
            documentRepository.save(document);

            List<DocumentChunk> storedChunks = new java.util.ArrayList<>(chunks.size());
            List<double[]> vectors = new java.util.ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                var c = chunks.get(i);
                float[] vector = embeddings.get(i).getOutput();
                if (vector == null || vector.length == 0) {
                    throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED, "Empty embedding returned");
                }
                embeddingDimensions.requireMatch(vector.length, "chunk embedding");
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

            document.markReady(pages.size(), chunks.size());
            documentRepository.save(document);
            log.info("Document {} processed: {} pages, {} chunks", document.getId(), pages.size(), chunks.size());
        } catch (AppException e) {
            fail(document, e.getMessage());
        } catch (IOException e) {
            fail(document, "Failed to read stored PDF: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error processing document {}", documentId, e);
            fail(document, "Unexpected processing error");
        }
    }

    private void fail(Document document, String message) {
        try {
            document.markFailed(message);
            documentRepository.save(document);
        } catch (Exception ex) {
            log.error("Failed to persist failure state for document {}", document.getId(), ex);
        }
    }
}