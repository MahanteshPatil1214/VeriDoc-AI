package com.veridoc.ai.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link DeterministicChatModel} that also answers the query-rewriting prompt:
 * when the system message asks for a standalone search query it returns the
 * fixed {@link #REWRITE_RESULT} and records the call, so an IT can assert the
 * rewrite step ran and fed retrieval.
 */
public class RewriteAwareChatModel extends DeterministicChatModel {

    private static final AtomicBoolean REWRITE_SEEN = new AtomicBoolean(false);
    private static final String MARKER = "standalone search query";

    /** Identical to the query ChatRagIT already proves is retrievable. */
    private static final String REWRITE_RESULT =
            "VeriDoc AI verifies claims against uploaded source documents";

    public static boolean rewriteSeen() {
        return REWRITE_SEEN.get();
    }

    public static void reset() {
        REWRITE_SEEN.set(false);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getSystemMessage() == null
                ? "" : prompt.getSystemMessage().getText();
        if (system.contains(MARKER)) {
            REWRITE_SEEN.set(true);
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(REWRITE_RESULT))));
        }
        return super.call(prompt);
    }
}