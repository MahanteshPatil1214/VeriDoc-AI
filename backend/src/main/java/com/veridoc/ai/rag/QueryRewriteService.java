package com.veridoc.ai.rag;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.veridoc.ai.config.properties.RagProperties;

/**
 * Rewrites the user's question into a standalone retrieval query before
 * embedding, controlled by {@code veridoc.rag.enable-query-rewriting}.
 *
 * <p>The rewrite is deliberately constrained to output the query alone: any
 * preamble from the model is stripped, and a blank or implausibly long result
 * falls back to the original question so retrieval can never be starved by a
 * misbehaving model.
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final Pattern VERBAL_OVERTHINKING =
            Pattern.compile("(?i)^\\s*(?:query|search(?:\\s+query)?|rewritten(?:\\s+query)?)\\s*[:\\-]\\s*");

    private static final int MAX_REWRITE_LENGTH = 300;

    private static final String SYSTEM_INSTRUCTION =
            "You are the retrieval step of an evidence-grounded document assistant. "
                    + "Rewrite the user's question into ONE standalone search query that, if run "
                    + "against a document corpus, would retrieve the passages that answer it. "
                    + "Keep the document's language. Reply with only the query itself: no labels, "
                    + "no introduction, no answer, no markdown.";

    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public QueryRewriteService(ChatModel chatModel, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * @return the rewritten query, or {@code question} unchanged when rewriting
     * is disabled or the model output is unusable.
     */
    public String rewrite(String question) {
        if (!ragProperties.enableQueryRewriting() || question == null || question.isBlank()) {
            return question;
        }
        String rewritten;
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_INSTRUCTION),
                    new UserMessage(question))));
            rewritten = response.getResult().getOutput().getText();
        } catch (RuntimeException e) {
            log.warn("Query rewriting failed; using the original question: {}", e.getMessage());
            return question;
        }
        String cleaned = VERBAL_OVERTHINKING.matcher(rewritten == null ? "" : rewritten)
                .replaceFirst("").strip();
        if (cleaned.isBlank() || cleaned.length() > MAX_REWRITE_LENGTH) {
            log.debug("Query rewrite unusable (blank or >{} chars); using the original question",
                    MAX_REWRITE_LENGTH);
            return question;
        }
        return cleaned;
    }
}