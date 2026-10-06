package com.veridoc.ai.document.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.veridoc.ai.document.domain.Document;
import com.veridoc.ai.document.domain.DocumentStatus;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * Ownership-scoped lookup. Never expose a {@code findById} based access
     * path for user-facing endpoints; this is the shape services must use.
     */
    Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Document> findByOwnerIdAndSha256AndStatusNot(UUID ownerId,
                                                         String sha256,
                                                         DocumentStatus excluded);

    Page<Document> findByOwnerIdAndStatusNot(UUID ownerId,
                                              DocumentStatus excluded,
                                              Pageable pageable);

    Page<Document> findByOwnerIdAndStatusNotAndFilenameContainingIgnoreCase(
            UUID ownerId, DocumentStatus excluded, String filenameFragment, Pageable pageable);

    List<Document> findByOwnerIdAndStatusIn(UUID ownerId, Collection<DocumentStatus> statuses);

    @Query("""
            select d from Document d
            where d.id in :ids and d.ownerId = :ownerId and d.status = :status
            """)
    List<Document> findReadyOwned(@Param("ownerId") UUID ownerId,
                                  @Param("ids") Collection<UUID> ids,
                                  @Param("status") DocumentStatus status);

    long countByOwnerIdAndStatusNot(UUID ownerId, DocumentStatus excluded);
}