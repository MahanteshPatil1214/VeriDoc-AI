package com.veridoc.ai.document.ingestion.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.StorageProperties;

/**
 * Extracts text from PDFs on a per-page basis. Page numbers are preserved for
 * citation metadata, and extraction enforces the configured maximum page
 * ceiling.
 */
public class PdfExtractor {

    public record PageText(int pageNumber, String text) {
    }

    private final StorageProperties storageProperties;

    public PdfExtractor(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    public List<PageText> extract(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Empty PDF content");
        }

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(new ByteArrayInputStream(pdfBytes)))) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "PDF contains no pages");
            }
            if (pageCount > storageProperties.maxPages()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "PDF exceeds maximum allowed pages (" + storageProperties.maxPages() + ")");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            List<PageText> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                // Normalize whitespace and control characters. PDFs may contain
                // non-printable bytes that are not useful for retrieval.
                String normalized = normalize(text);
                pages.add(new PageText(page, normalized));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Password-protected PDFs are not supported");
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        // Collapse whitespace and strip non-printable C0 control characters
        // except tab/newline/CR (PDFTextStripper usually emits spaces/newlines).
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("[ \t]+", " ").replaceAll("[\r\n]+", "\n").trim();
    }
}