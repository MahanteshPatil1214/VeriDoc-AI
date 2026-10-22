package com.veridoc.ai.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit, binary relevance labels for one evaluation case: which documents,
 * and optionally which pages, count as relevant.
 *
 * <p>Relevance is never inferred from the retrieved list and never graded. A
 * passage is relevant when its document is labelled and, if the case narrows to
 * pages, its page is one of the labelled pages. That is the whole model: no
 * nDCG, no similarity cut-offs, no "close enough".
 *
 * <p>A label set describes <em>targets</em>, not a count of chunks. A document
 * labelled without pages is one target ("any page of a.pdf"); labelling pages 2
 * and 4 of a.pdf is two targets. Recall is measured against targets so a
 * document cannot inflate recall by contributing several chunks.
 *
 * <p>Chunk ids are not authorable labels: they are random per ingest. Page is
 * the finest stable granularity the dataset can express.
 */
public record RelevanceLabels(List<String> documents, Set<Integer> pages) {

    public RelevanceLabels {
        documents = documents == null ? List.of() : List.copyOf(documents);
        pages = pages == null ? Set.of() : Set.copyOf(pages);
    }

    public static RelevanceLabels of(List<String> documents, List<Integer> pages) {
        return new RelevanceLabels(documents,
                pages == null ? null : new LinkedHashSet<>(pages));
    }

    public static RelevanceLabels document(String filename) {
        return new RelevanceLabels(filename == null ? List.of() : List.of(filename), Set.of());
    }

    public static RelevanceLabels none() {
        return new RelevanceLabels(List.of(), Set.of());
    }

    /** True when the case declares no relevant evidence at all (an abstention case). */
    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /** Document-level match; page labels are ignored. Used by citation precision. */
    public boolean matchesDocument(EvidencePassage passage) {
        return passage != null
                && passage.documentFilename() != null
                && documents.contains(passage.documentFilename());
    }

    /** Full match: document plus, when pages are labelled, the page. */
    public boolean matches(EvidencePassage passage) {
        if (!matchesDocument(passage)) {
            return false;
        }
        if (pages.isEmpty()) {
            return true;
        }
        return passage.pageNumber() != null && pages.contains(passage.pageNumber());
    }

    /** The labelled targets, one per (document, page) pair; page {@code null} means any page. */
    public List<RelevanceTarget> targets() {
        List<RelevanceTarget> targets = new ArrayList<>();
        for (String document : documents) {
            if (pages.isEmpty()) {
                targets.add(new RelevanceTarget(document, null));
            } else {
                for (Integer page : pages) {
                    targets.add(new RelevanceTarget(document, page));
                }
            }
        }
        return targets;
    }

    /** A single labelled (document, page-or-any) pair. */
    public record RelevanceTarget(String document, Integer page) {

        public boolean matches(EvidencePassage passage) {
            if (passage == null || passage.documentFilename() == null) {
                return false;
            }
            if (!document.equals(passage.documentFilename())) {
                return false;
            }
            return page == null || page.equals(passage.pageNumber());
        }
    }
}
