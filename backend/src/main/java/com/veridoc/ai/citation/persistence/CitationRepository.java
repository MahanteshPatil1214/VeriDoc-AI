package com.veridoc.ai.citation.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.veridoc.ai.citation.domain.Citation;

public interface CitationRepository extends JpaRepository<Citation, UUID> {

    List<Citation> findByMessageIdOrderByOrdinalAsc(UUID messageId);

    Optional<Citation> findByIdAndMessageId(UUID id, UUID messageId);

    long countByMessageId(UUID messageId);
}