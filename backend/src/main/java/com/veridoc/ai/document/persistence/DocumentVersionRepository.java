package com.veridoc.ai.document.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.veridoc.ai.document.domain.DocumentVersion;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    /** Removes every version of a document; used when deleting or retrying it. */
    long deleteByDocumentId(UUID documentId);
}