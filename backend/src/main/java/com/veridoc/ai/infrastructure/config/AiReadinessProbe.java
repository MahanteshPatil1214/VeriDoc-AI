package com.veridoc.ai.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.veridoc.ai.config.properties.AiProperties;
import com.veridoc.ai.config.properties.EmbeddingProperties;

import jakarta.annotation.PostConstruct;

/**
 * Fails fast, at startup, when the AI layer cannot be configured.
 *
 * <p>Chat and embeddings come from different providers (Groq and Gemini), so
 * each has its own key and its own failure mode. Spring AI's own failure
 * ("Could not resolve placeholder 'GROQ_API_KEY'" or similar) is technically
 * accurate but useless to an operator; this probe reports the actual cause.
 *
 * <p>Neither provider key is ever logged; only its presence is reported.
 */
@Component
public class AiReadinessProbe {

    private static final Logger log = LoggerFactory.getLogger(AiReadinessProbe.class);

    private final ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModel;
    private final ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel;
    private final EmbeddingProperties embeddingProperties;
    private final AiProperties aiProperties;

    public AiReadinessProbe(ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModel,
                            ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
                            EmbeddingProperties embeddingProperties,
                            AiProperties aiProperties) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.embeddingProperties = embeddingProperties;
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    void verify() {
        boolean embeddingsAvailable = embeddingModel.getIfAvailable() != null;
        boolean chatAvailable = chatModel.getIfAvailable() != null;

        if (!embeddingsAvailable || !chatAvailable) {
            StringBuilder missing = new StringBuilder();
            if (!embeddingsAvailable) {
                missing.append("  - EmbeddingModel (Gemini): requires GEMINI_API_KEY")
                        .append(System.lineSeparator());
            }
            if (!chatAvailable) {
                missing.append("  - ChatModel (Groq): requires GROQ_API_KEY")
                        .append(System.lineSeparator());
            }

            throw new IllegalStateException(String.join("\n",
                    "The AI layer is not fully configured.",
                    "",
                    "Missing components:",
                    missing.toString().stripTrailing(),
                    "",
                    "Both keys are read on the backend only and are never exposed",
                    "to the frontend."));
        }

        log.info("AI layer ready: Groq chat '{}' + Gemini embedding '{}' ({} dimensions, v{})",
                aiProperties.chatModel(), embeddingProperties.model(),
                embeddingProperties.dimensions(), embeddingProperties.version());
    }
}