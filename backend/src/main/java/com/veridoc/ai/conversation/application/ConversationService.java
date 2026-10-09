package com.veridoc.ai.conversation.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.RetrievalProperties;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ConversationDocumentRef;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ConversationListItem;
import com.veridoc.ai.conversation.api.dto.ChatDtos.ConversationResponse;
import com.veridoc.ai.conversation.domain.Conversation;
import com.veridoc.ai.conversation.domain.ConversationDocument;
import com.veridoc.ai.conversation.persistence.ConversationDocumentRepository;
import com.veridoc.ai.conversation.persistence.ConversationRepository;
import com.veridoc.ai.document.domain.Document;
import com.veridoc.ai.document.domain.DocumentStatus;
import com.veridoc.ai.document.persistence.DocumentRepository;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

/**
 * Conversation lifecycle: create, list, attach documents, read detail.
 * Attaching only ever allows READY documents already owned by the caller, so a
 * conversation cannot be widened into other users' content.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationDocumentRepository conversationDocumentRepository;
    private final DocumentRepository documentRepository;
    private final RetrievalProperties retrievalProperties;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationDocumentRepository conversationDocumentRepository,
                               DocumentRepository documentRepository,
                               RetrievalProperties retrievalProperties) {
        this.conversationRepository = conversationRepository;
        this.conversationDocumentRepository = conversationDocumentRepository;
        this.documentRepository = documentRepository;
        this.retrievalProperties = retrievalProperties;
    }

    public ConversationResponse create(AuthenticatedUser user, String title) {
        Conversation conversation = Conversation.create(UUID.randomUUID(), user.userId(), title);
        conversationRepository.save(conversation);
        return new ConversationResponse(conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), List.of());
    }

    public List<ConversationListItem> list(AuthenticatedUser user) {
        return conversationRepository
                .findByOwnerId(user.userId(),
                        PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent()
                .stream()
                .map(c -> new ConversationListItem(c.getId(), c.getTitle(), c.getUpdatedAt()))
                .toList();
    }

    public ConversationResponse attach(AuthenticatedUser user, UUID conversationId,
                                       List<UUID> documentIds) {
        Conversation conversation = conversationRepository
                .findByIdAndOwnerId(conversationId, user.userId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));

        Set<UUID> alreadyAttached = new HashSet<>(
                conversationDocumentRepository.findDocumentIds(conversationId));
        int room = retrievalProperties.maxDocumentsPerConversation() - alreadyAttached.size();
        if (room <= 0) {
            throw new AppException(ErrorCode.CONFLICT,
                    "This conversation has reached the maximum number of documents");
        }

        int added = 0;
        for (UUID documentId : documentIds) {
            if (alreadyAttached.contains(documentId)) {
                continue;
            }
            Document document = documentRepository.findByIdAndOwnerId(documentId, user.userId())
                    .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND,
                            "Document not found"));
            if (document.getStatus() != DocumentStatus.READY) {
                throw new AppException(ErrorCode.DOCUMENT_NOT_READY,
                        "Document is not ready for chat");
            }
            conversationDocumentRepository.save(ConversationDocument.attach(conversationId, documentId));
            alreadyAttached.add(documentId);
            added++;
            if (added >= room) {
                break;
            }
        }
        return detail(user, conversationId);
    }

    public ConversationResponse detail(AuthenticatedUser user, UUID conversationId) {
        Conversation conversation = conversationRepository
                .findByIdAndOwnerId(conversationId, user.userId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));

        List<UUID> attachedIds = conversationDocumentRepository.findDocumentIds(conversationId);
        Map<UUID, Document> readyDocs = documentRepository
                .findReadyOwned(user.userId(), attachedIds, DocumentStatus.READY)
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        List<ConversationDocumentRef> refs = attachedIds.stream()
                .filter(readyDocs::containsKey)
                .map(id -> new ConversationDocumentRef(id, readyDocs.get(id).getFilename()))
                .toList();

        return new ConversationResponse(conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), refs);
    }
}