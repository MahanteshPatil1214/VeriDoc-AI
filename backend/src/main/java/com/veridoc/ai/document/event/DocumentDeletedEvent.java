package com.veridoc.ai.document.event;

import java.util.UUID;

/**
 * Published after a document has been soft-deleted. Other modules (for example
 * conversations) use it to drop references they hold to the document without the
 * document module needing to depend on them.
 */
public record DocumentDeletedEvent(UUID documentId) {
}
