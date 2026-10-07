package com.veridoc.ai.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small in-memory PDFs via PDFBox for integration tests. */
public final class TestPdfs {

    private TestPdfs() {
    }

    /** A single-page PDF whose text is a blank-separated {@code message}. */
    public static byte[] singlePage(String message) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                String[] lines = message.split("(?<=[.!?])\\s+");
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Placeholder text comfortably above the default {@code minTokens} (40). */
    public static String longEnoughParagraph() {
        return "VeriDoc AI verifies claims against uploaded source documents. "
                + "Every answer quotes the exact chunk it relied upon. "
                + "Answers that cannot be grounded in the documents are refused. "
                + "Users may upload contracts, manuals, or policy PDFs. "
                + "The system performs similarity search over stored embeddings. "
                + "Retrieved text is evidence, never executable instructions. "
                + "Citations preserve the page and chunk identifiers. "
                + "This paragraph exists purely so the chunker produces tokens.";
    }
}