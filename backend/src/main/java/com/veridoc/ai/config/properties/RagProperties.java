package com.veridoc.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** RAG orchestration tuning and the canonical "no evidence" answer. */
@ConfigurationProperties(prefix = "veridoc.rag")
public record RagProperties(

        @DefaultValue("8") int historyMessages,

        @DefaultValue("12000") int maxContextTokens,

        @DefaultValue("true") boolean enableQueryRewriting,

        @DefaultValue("I couldn't find enough information in the uploaded documents to answer that.")
        String noEvidenceAnswer,

        @DefaultValue("false") boolean debugEnabled
) {
    public RagProperties {
        if (historyMessages < 0) {
            throw new IllegalStateException("veridoc.rag.history-messages must be >= 0");
        }
        if (maxContextTokens <= 0) {
            throw new IllegalStateException("veridoc.rag.max-context-tokens must be positive");
        }
        if (noEvidenceAnswer == null || noEvidenceAnswer.isBlank()) {
            throw new IllegalStateException("veridoc.rag.no-evidence-answer must not be blank");
        }
    }
}