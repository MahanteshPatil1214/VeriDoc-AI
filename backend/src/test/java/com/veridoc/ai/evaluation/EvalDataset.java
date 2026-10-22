package com.veridoc.ai.evaluation;

import java.util.List;

/** A named corpus plus the cases graded against it. */
public record EvalDataset(
        String name,
        List<CorpusDocument> corpus,
        List<EvalCase> cases
) {
    public EvalDataset {
        corpus = corpus == null ? List.of() : List.copyOf(corpus);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * One corpus document.
     *
     * <p>{@code pages} is the multi-page form: one PDF page per entry, so page
     * labels in cases and the chunker's page boundaries line up exactly.
     * {@code text} remains supported for single-page documents; exactly one of
     * the two must be set.
     */
    public record CorpusDocument(String filename, String text, List<String> pages) {

        public CorpusDocument {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        /** Page texts in order; a {@code text}-only document is a single page. */
        public List<String> pageTexts() {
            return pages.isEmpty() ? List.of(text) : pages;
        }
    }
}
