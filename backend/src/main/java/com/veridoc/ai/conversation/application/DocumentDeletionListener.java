package com.veridoc.ai.conversation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.conversation.persistence.ConversationDocumentRepository;
import com.veridoc.ai.document.event.DocumentDeletedEvent;

/**
 * Detaches a deleted document from every conversation it was attached to.
 *
 * <p>Reads already filter attachments by document status, so a stale link is not
 * an information leak. It is still removed because attachment capacity is
 * counted from the link table and a deleted document must not silently consume
 * one of a conversation's document slots.
 */
@Component
public class DocumentDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionListener.class);

    private final ConversationDocumentRepository conversationDocumentRepository;

    public DocumentDeletionListener(ConversationDocumentRepository conversationDocumentRepository) {
        this.conversationDocumentRepository = conversationDocumentRepository;
    }

    @EventListener
    @Transactional
    public void onDocumentDeleted(DocumentDeletedEvent event) {
        int detached = conversationDocumentRepository.deleteByDocumentId(event.documentId());
        if (detached > 0) {
            log.debug("Detached deleted document {} from {} conversation(s)", event.documentId(), detached);
        }
    }
}
