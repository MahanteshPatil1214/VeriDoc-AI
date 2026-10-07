package com.veridoc.ai.document.ingestion.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import com.veridoc.ai.common.security.Hashing;
import com.veridoc.ai.config.properties.ChunkingProperties;

/**
 * Token-aware chunking that preserves page boundaries and attempts to keep
 * sentences intact. Chunks shorter than {@code minTokens} are dropped to avoid
 * polluting retrieval with low-signal fragments.
 */
public class TokenChunkerBase {

    public record Chunk(
            String content,
            int pageNumber,
            int chunkIndex,
            int tokenCount,
            String contentHash
    ) {
    }

    private final ChunkingProperties properties;

    public TokenChunkerBase(ChunkingProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> chunk(List<com.veridoc.ai.document.ingestion.pdf.PdfExtractor.PageText> pages) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (var page : pages) {
            String text = page.text();
            if (text.isBlank()) {
                continue;
            }
            List<String> pieces = splitBySentence(text);
            StringBuilder current = new StringBuilder();
            for (String piece : pieces) {
                int proposed = tokenCount(current.toString()) + tokenCount(piece) + 1;
                if (proposed > properties.maxTokens() && current.length() > 0) {
                    flush(chunks, page.pageNumber(), chunkIndex++, current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ').append(piece);
                } else {
                    current.append(piece);
                }
            }
            if (current.length() > 0) {
                flush(chunks, page.pageNumber(), chunkIndex++, current.toString());
            }
        }
        return chunks;
    }

    private void flush(List<Chunk> chunks, int pageNumber, int chunkIndex, String content) {
        int tokens = tokenCount(content);
        if (tokens < properties.minTokens()) {
            return;
        }
        chunks.add(new Chunk(content, pageNumber, chunkIndex, tokens, Hashing.sha256Hex(content)));
    }

    private List<String> splitBySentence(String text) {
        List<String> parts = new ArrayList<>();
        if (text.isBlank()) {
            return parts;
        }
        String[] candidates = text.split("(?<=[.!?])\\s+|[\\r\\n]+");
        for (String candidate : candidates) {
            String trimmed = candidate.trim();
            if (!trimmed.isBlank()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            parts.add(text.trim());
        }
        return parts;
    }

    private int tokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        StringTokenizer st = new StringTokenizer(text);
        return st.countTokens();
    }
}