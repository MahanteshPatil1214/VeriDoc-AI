package com.veridoc.ai.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.StorageProperties;

import jakarta.annotation.PostConstruct;

/**
 * Content-addressed local storage for uploaded originals.
 *
 * <p>Paths are derived from the document UUID only. The sanitised user-supplied
 * filename is stored in the database for display and is never joined into a
 * filesystem path, so path traversal is impossible by construction.
 *
 * <p>Layout: {@code <root>/<first2hex>/<uuid>.pdf}
 */
@Component
public class LocalDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);
    private static final String PDF_EXTENSION = ".pdf";

    private final StorageProperties properties;
    private final Path root;

    public LocalDocumentStorage(StorageProperties properties) {
        this.properties = properties;
        this.root = properties.root().toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialise() {
        try {
            Files.createDirectories(root);
            log.info("Document storage root ready at {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage root: " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Streams the upload to its final location atomically, first computing the
     * SHA-256 while writing so the bytes are never re-read.
     */
    public StoredFile store(UUID documentId, InputStream input) {
        Path target = resolveFor(documentId);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".part");
            try {
                String sha256;
                long bytes;
                try (var in = input) {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var out = Files.newOutputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        long total = 0;
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > properties.maxFileSize()) {
                                throw new AppException(ErrorCode.DOCUMENT_TOO_LARGE,
                                        "The uploaded document exceeds the maximum allowed size.");
                            }
                            digest.update(buffer, 0, read);
                            out.write(buffer, 0, read);
                        }
                        bytes = total;
                    }
                    sha256 = java.util.HexFormat.of().formatHex(digest.digest());
                }
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return new StoredFile(target.toString(), bytes, sha256);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (AppException e) {
            throw e;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            log.error("Failed to store document {}", documentId, e);
            throw new AppException(ErrorCode.INTERNAL_ERROR, "Failed to store the uploaded document.");
        }
    }

    /** Reads the stored original. Ownership is checked by the caller first. */
    public byte[] read(String storagePath) {
        Path path = safeResolve(storagePath);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new AppException(ErrorCode.DOCUMENT_PROCESSING_FAILED,
                    "The stored document could not be read.");
        }
    }

    public boolean exists(String storagePath) {
        return Files.isRegularFile(safeResolve(storagePath), LinkOption.NOFOLLOW_LINKS);
    }

    public long sizeOf(String storagePath) {
        try {
            return Files.size(safeResolve(storagePath));
        } catch (IOException e) {
            return -1;
        }
    }

    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(safeResolve(storagePath));
        } catch (IOException e) {
            log.warn("Could not delete stored document at {}", storagePath, e);
        }
    }

    /**
     * Resolves a database-stored path and refuses anything that escapes the
     * storage root, including via symlinks.
     */
    private Path safeResolve(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid storage reference.");
        }
        Path candidate;
        try {
            candidate = Path.of(storagePath);
        } catch (java.nio.file.InvalidPathException e) {
            log.warn("Rejected malformed storage path: {}", storagePath);
            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid storage reference.");
        }
        Path absolute = candidate.isAbsolute() ? candidate : root.resolve(candidate);
        Path normalised = absolute.normalize();
        if (!normalised.startsWith(root)) {
            log.warn("Rejected storage path outside root: {}", storagePath);
            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid storage reference.");
        }
        // Follow symlinks and re-check: a link inside the root could still
        // point at an arbitrary file on disk.
        Path real;
        try {
            real = normalised.toRealPath();
        } catch (IOException e) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid storage reference.");
        }
        if (!real.startsWith(root)) {
            log.warn("Rejected storage path resolving outside root: {}", storagePath);
            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid storage reference.");
        }
        return real;
    }

    private Path resolveFor(UUID documentId) {
        String hex = documentId.toString().replace("-", "");
        return root.resolve(hex.substring(0, 2)).resolve(hex + PDF_EXTENSION).normalize();
    }

    public record StoredFile(String storagePath, long sizeBytes, String sha256) {
    }
}