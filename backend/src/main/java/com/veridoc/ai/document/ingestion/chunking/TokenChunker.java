package com.veridoc.ai.document.ingestion.chunking;

import org.springframework.stereotype.Component;

import com.veridoc.ai.config.properties.ChunkingProperties;

@Component
public class TokenChunker extends com.veridoc.ai.document.ingestion.chunking.TokenChunkerBase {

    public TokenChunker(ChunkingProperties properties) {
        super(properties);
    }
}