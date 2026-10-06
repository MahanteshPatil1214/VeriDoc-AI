package com.veridoc.ai.document.ingestion.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.veridoc.ai.config.properties.ChunkingProperties;
import com.veridoc.ai.document.ingestion.pdf.PdfExtractor;

class TokenChunkerTest {

    private final ChunkingProperties props = new ChunkingProperties(700, 100, 40);
    private final TokenChunker chunker = new TokenChunker(props);

    @Test
    void chunksSinglePageIntoCoherentPieces() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("This is a test sentence about VeriDoc AI retrieval. ");
        }
        String text = sb.toString().trim();
        List<PdfExtractor.PageText> pages = List.of(new PdfExtractor.PageText(1, text));
        var chunks = chunker.chunk(pages);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).content()).isNotBlank();
        assertThat(chunks.get(0).tokenCount()).isGreaterThan(0);
    }

    @Test
    void skipsEmptyPages() {
        List<PdfExtractor.PageText> pages = List.of(
                new PdfExtractor.PageText(1, "   "),
                new PdfExtractor.PageText(2, "Hello world. This is a small chunk for testing purposes. ")
        );
        var chunks = chunker.chunk(pages);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(2);
    }
}