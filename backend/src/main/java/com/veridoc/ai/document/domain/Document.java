package com.veridoc.ai.document.domain;

import java.time.Instant;
import java.util.UUID;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An uploaded PDF owned by exactly one user.
 *
 * <p>{@code sha256} plus {@code ownerId} is covered by a partial unique index,
 * which is what makes duplicate detection user-aware at the database level.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** Sanitised display name; never used to build a filesystem path. */
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_stage", length = 32)
    private DocumentStatus processingStage;

    @Column(name = "processing_error", length = 2000)
    private String processingError;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    protected Document() {
    }

    private Document(UUID id, UUID ownerId, String filename, String contentType,
                     long sizeBytes, String sha256, String storagePath) {
        this.id = id;
        this.ownerId = ownerId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storagePath = storagePath;
        this.status = DocumentStatus.UPLOADING;
        this.processingStage = DocumentStatus.UPLOADING;
        this.version = 1;
    }

    public static Document create(UUID id, UUID ownerId, String filename, String contentType,
                                  long sizeBytes, String sha256, String storagePath) {
        return new Document(id, ownerId, filename, contentType, sizeBytes, sha256, storagePath);
    }

    /**
     * Advances the pipeline, rejecting illegal transitions so a late async
     * callback cannot resurrect a deleted or already-ready document.
     */
    public void transitionTo(DocumentStatus next) {
        if (status == next) {
            return;
        }
        if (!status.canTransitionTo(next)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Cannot move document from " + status + " to " + next + ".");
        }
        status = next;
        processingStage = next;
        if (next != DocumentStatus.FAILED) {
            processingError = null;
        }
    }

    public void markFailed(String error) {
        status = DocumentStatus.FAILED;
        processingStage = DocumentStatus.FAILED;
        processingError = truncate(error);
    }

    public void markProcessing(DocumentStatus stage) {
        if (status == DocumentStatus.DELETED) {
            throw new AppException(ErrorCode.CONFLICT, "Document has been deleted.");
        }
        status = DocumentStatus.PROCESSING;
        processingStage = stage;
        processingError = null;
    }

    public void markReady(int pageCount, int chunkCount) {
        this.pageCount = pageCount;
        this.chunkCount = chunkCount;
        this.status = DocumentStatus.READY;
        this.processingStage = DocumentStatus.READY;
        this.processingError = null;
    }

    public void softDelete() {
        status = DocumentStatus.DELETED;
        processingStage = DocumentStatus.DELETED;
        processingError = null;
    }

    public boolean isOwnedBy(UUID candidate) {
        return ownerId != null && ownerId.equals(candidate);
    }

    /** Guards against use-after-delete inside the async pipeline. */
    public void requireNotDeleted() {
        if (status == DocumentStatus.DELETED) {
            throw new AppException(ErrorCode.CONFLICT, "Document has been deleted.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public DocumentStatus getProcessingStage() {
        return processingStage;
    }

    public String getProcessingError() {
        return processingError;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown processing error.";
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= 2000 ? flat : flat.substring(0, 1997) + "...";
    }
}