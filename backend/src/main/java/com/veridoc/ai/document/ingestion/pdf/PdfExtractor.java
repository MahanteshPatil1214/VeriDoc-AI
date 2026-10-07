package com.veridoc.ai.document.ingestion.pdf;

import org.springframework.stereotype.Component;

import com.veridoc.ai.config.properties.StorageProperties;

@Component
public class PdfExtractor extends com.veridoc.ai.document.ingestion.pdf.PdfExtractorBase {

    public PdfExtractor(StorageProperties storageProperties) {
        super(storageProperties);
    }
}