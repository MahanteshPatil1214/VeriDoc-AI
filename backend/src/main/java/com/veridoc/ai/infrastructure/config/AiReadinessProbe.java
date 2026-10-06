package com.veridoc.ai.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.veridoc.ai.config.properties.EmbeddingProperties;

import jakarta.annotation.PostConstruct;

/**
 * Fails fast, at startup, when the AI layer cannot be configured.
 *
 * <p>Spring AI's own failure ("Google GenAI project-id must be set!") is
 * technically accurate but useless to an operator. This probe reports the
 * actual cause: a missing API key.
 *
 * <p>The Gemini key is never logged; only its presence is reported.
 */
@Component
public class AiReadinessProbe {

    private static final Logger log = LoggerFactory.getLogger(AiReadinessProbe.class);

    private final ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModel;
    private final ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel;
    private final EmbeddingProperties embeddingProperties;

    public AiReadinessProbe(ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModel,
                            ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
                            EmbeddingProperties embeddingProperties) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.embeddingProperties = embeddingProperties;
    }

    @PostConstruct
    void verify() {
        boolean embeddingsAvailable = embeddingModel.getIfAvailable() != null;
        boolean chatAvailable = chatModel.getIfAvailable() != null;

        if (!embeddingsAvailable || !chatAvailable) {
            String key = System.getenv("GEMINI_API_KEY");
            boolean keyPresent = key != null && !key.isBlank();

            if (!keyPresent) {
                throw new IllegalStateException(String.join("\n",
                        "Gemini is not configured.",
                        "",
                        "Set the GEMINI_API_KEY environment variable (or Spring property",
                        "spring.ai.google.genai.api-key) before starting the backend.",
                        "",
                        "The key is read on the backend only and is never exposed to the frontend.",
                        "",
                        "Missing components:",
                        embeddingsAvailable ? "  - none" : "  - EmbeddingModel",
                        chatAvailable ? "  - none" : "  - ChatModel"));
            }
            throw new IllegalStateException(
                    "Gemini API key is present but the AI clients could not be created. "
                            + "Verify the key is valid and that the configured models exist.");
        }

        log.info("AI layer ready: chat model and embedding model '{}' ({} dimensions, v{})",
                embeddingProperties.model(), embeddingProperties.dimensions(),
                embeddingProperties.version());
    }
}