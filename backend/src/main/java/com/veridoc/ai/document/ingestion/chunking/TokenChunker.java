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
public class TokenChunker {

    public record Chunk(
            String content,
            int pageNumber,
            int chunkIndex,
            int tokenCount,
            String contentHash
    ) {
    }

    private final ChunkingProperties properties;

    public TokenChunker(ChunkingProperties properties) {
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
        // Apply overlap: if not the first chunk on this call chain, overlap is
        // handled by the caller's state in a real implementation. For this
        // iteration we keep the simple greedy split; overlap can be refined
        // without breaking the schema.
        chunks.add(new Chunk(content, pageNumber, chunkIndex, tokens, Hashing.sha256Hex(content)));
    }

    private List<String> splitBySentence(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                int end = i + 1;
                while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                String part = text.substring(start, Math.min(end, text.length())).trim();
                if (!part.isBlank()) {
                    parts.add(part);
                }
                start = end;
                if (start >= text.length()) {
                    break;
                }
            }
        }
        if (start < text.length()) {
            String part = text.substring(start).trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private int tokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // Rough but deterministic: split on whitespace. The configured
        // thresholds match this approximation for the intended PDF corpus.
        StringTokenizer st = new StringTokenizer(text);
        return st.countTokens();
    }
}