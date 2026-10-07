package com.veridoc.ai.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.common.security.FileNameSanitizer;
import com.veridoc.ai.common.security.Hashing;
import com.veridoc.ai.config.properties.StorageProperties;
import com.veridoc.ai.document.api.dto.DocumentDtos.DocumentListItem;
import com.veridoc.ai.document.api.dto.DocumentDtos.DocumentStatusResponse;
import com.veridoc.ai.document.api.dto.DocumentDtos.UploadResponse;
import com.veridoc.ai.document.domain.Document;
import com.veridoc.ai.document.domain.DocumentStatus;
import com.veridoc.ai.document.persistence.DocumentRepository;
import com.veridoc.ai.document.storage.LocalDocumentStorage;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

/**
 * Document upload and metadata operations.
 *
 * <p>Owner identity is always taken from the authenticated principal, never
 * from request parameters. The storage root and size/page ceilings are enforced
 * by configuration.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final LocalDocumentStorage storage;
    private final StorageProperties storageProperties;


    private final com.veridoc.ai.document.ingestion.processing.DocumentProcessor documentProcessor;

    public DocumentService(DocumentRepository documentRepository,
                           LocalDocumentStorage storage,
                           StorageProperties storageProperties,
                           com.veridoc.ai.document.ingestion.processing.DocumentProcessor documentProcessor) {
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.documentProcessor = documentProcessor;
    }

    /**
     * Uploads a single PDF for the authenticated user. The file is written to
     * storage and a document row is created in {@link DocumentStatus#UPLOADING}.
     */
    @Transactional
    public UploadResponse upload(AuthenticatedUser user, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "File is required");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Filename is required");
        }

        String sanitized = com.veridoc.ai.common.security.FileNameSanitizer.sanitizeDisplayName(originalFilename);
        if (sanitized.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Filename contains only invalid characters");
        }

        if (file.getSize() > storageProperties.maxFileSize()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "File exceeds maximum allowed size of " + storageProperties.maxFileSize());
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.equalsIgnoreCase("application/pdf")
                && !contentType.equalsIgnoreCase("application/x-pdf")) {
            // Do not blindly trust the client-provided content-type; PDFBox will
            // validate the bytes during extraction. For now we warn, but allow
            // the upload to proceed so extraction can fail with a precise error.
            log.debug("Unusual content-type '{}' for upload by user {}", contentType, user.userId());
        }

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        }

        String sha256 = Hashing.sha256Hex(bytes);

        var existing = documentRepository.findByOwnerIdAndSha256AndStatusNot(user.userId(), sha256, DocumentStatus.DELETED)
                .orElse(null);
        if (existing != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "A document with the same content already exists for this account");
        }

        UUID tempId = java.util.UUID.randomUUID();
        var stored = storage.store(tempId, file.getInputStream());
        String storagePath = stored.storagePath();

        Document document = Document.create(java.util.UUID.randomUUID(), user.userId(), sanitized,
                contentType != null ? contentType : "application/pdf", stored.sizeBytes(), stored.sha256(),
                storagePath);
        documentRepository.save(document);

        log.info("Uploaded document id={} owner={} filename={} size={}B",
                document.getId(), user.userId(), sanitized, document.getSizeBytes());

        triggerProcessingAfterCommit(document.getId());

        return new UploadResponse(document.getId(), document.getFilename(), document.getStatus().name(),
                document.getSizeBytes());
    }

    /**
     * Hands the document to the asynchronous pipeline only once the upload
     * transaction has committed. Starting the worker before commit would race
     * the row's visibility and could leave the document stuck in UPLOADING.
     */
    private void triggerProcessingAfterCommit(UUID documentId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            documentProcessor.process(documentId);
                        }
                    });
        } else {
            documentProcessor.process(documentId);
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<DocumentListItem> list(AuthenticatedUser user) {
        return documentRepository.findByOwnerIdAndStatusNot(user.userId(), DocumentStatus.DELETED,
                        org.springframework.data.domain.PageRequest.of(0, 1000, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(d -> new DocumentListItem(d.getId(), d.getFilename(), d.getStatus().name(),
                        d.getPageCount(), d.getChunkCount(), d.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse status(AuthenticatedUser user, UUID id) {
        Document document = documentRepository.findByIdAndOwnerId(id, user.userId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document not found"));
        return new DocumentStatusResponse(document.getId(), document.getStatus().name(),
                document.getProcessingStage() != null ? document.getProcessingStage().name() : null,
                document.getProcessingError(),
                document.getPageCount(), document.getChunkCount());
    }
}
