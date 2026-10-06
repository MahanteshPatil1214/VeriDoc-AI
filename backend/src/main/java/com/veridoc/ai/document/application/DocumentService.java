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
import com.veridoc.ai.infrastructure.storage.LocalDocumentStorage;
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
    private final FileNameSanitizer fileNameSanitizer;

    public DocumentService(DocumentRepository documentRepository,
                           LocalDocumentStorage storage,
                           StorageProperties storageProperties,
                           FileNameSanitizer fileNameSanitizer) {
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.fileNameSanitizer = fileNameSanitizer;
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

        String sanitized = fileNameSanitizer.sanitize(originalFilename);
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
            log.debug("Unusual content-type '{}' for upload by user {}", contentType, user.id());
        }

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        }

        String sha256 = Hashing.sha256(bytes);

        var existing = documentRepository.findByOwnerIdAndSha256AndStatusNot(user.id(), sha256, DocumentStatus.DELETED)
                .orElse(null);
        if (existing != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "A document with the same content already exists for this account");
        }

        String relativePath = storage.store(bytes, sanitized);
        Path storedPath = storage.resolve(relativePath);

        Document document = new Document();
        document.setOwnerId(user.id());
        document.setFilename(sanitized);
        document.setContentType(contentType != null ? contentType : "application/pdf");
        document.setSizeBytes(file.getSize());
        document.setSha256(sha256);
        document.setStoragePath(storedPath.toAbsolutePath().toString());
        document.setStatus(DocumentStatus.UPLOADING);
        documentRepository.save(document);

        log.info("Uploaded document id={} owner={} filename={} size={}B",
                document.getId(), user.id(), sanitized, document.getSizeBytes());

        return new UploadResponse(document.getId(), document.getFilename(), document.getStatus().name(),
                document.getSizeBytes());
    }

    @Transactional(readOnly = true)
    public java.util.List<DocumentListItem> list(AuthenticatedUser user) {
        return documentRepository.findByOwnerIdOrderByCreatedAtDesc(user.id())
                .stream()
                .map(d -> new DocumentListItem(d.getId(), d.getFilename(), d.getStatus().name(),
                        d.getPageCount(), d.getChunkCount(), d.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse status(AuthenticatedUser user, UUID id) {
        Document document = documentRepository.findByIdAndOwnerId(id, user.id())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document not found"));
        return new DocumentStatusResponse(document.getId(), document.getStatus().name(),
                document.getProcessingStage(), document.getProcessingError(),
                document.getPageCount(), document.getChunkCount());
    }
}