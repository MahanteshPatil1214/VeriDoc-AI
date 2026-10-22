package com.veridoc.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.veridoc.ai.document.persistence.RetrievedChunkRow;

/**
 * Resolves the citation markers in a generated answer against the exact
 * evidence bundle that was sent to the model.
 *
 * <p>This is a structural/provenance check only:
 * <ul>
 *   <li>markers must be well-formed and in range;</li>
 *   <li>a persisted citation is built from the retrieved row, never from the
 *       marker text, so an invented source cannot be created;</li>
 *   <li>duplicate markers collapse to one citation;</li>
 *   <li>malformed and out-of-range markers are ignored, not rewritten;</li>
 *   <li>an answer with no valid citation is replaced by the configured
 *       no-evidence answer.</li>
 * </ul>
 *
 * <p>It does <strong>not</strong> prove that the answer's prose is semantically
 * supported by the cited passage. That would require a verifier; callers must
 * not describe structural citation validation as faithfulness verification.
 */
public final class CitationMarkerResolver {

    /**
     * Canonical {@code [n]} markers plus the full-width forms some models emit
     * despite the prompt. Extra brackets, ranges and comma lists are malformed
     * and intentionally do not match.
     */
    private static final Pattern MARKER = Pattern.compile(
            "(?<!\\[)\\[(\\d{1,9})\\](?!\\])|【(\\d{1,9})(?:†[^】]*)?】");

    private CitationMarkerResolver() {
    }

    /** A cited passage: the marker ordinal and the exact evidence row it maps to. */
    public record CitedPassage(int ordinal, RetrievedChunkRow passage) {
    }

    /**
     * @param content           answer to persist (original, or the no-evidence
     *                          answer when no valid citation was found)
     * @param citedPassages     unique cited passages, ordered by ordinal
     * @param markerCount       total number of well-formed markers, including
     *                          duplicates and out-of-range ones
     * @param invalidMarkerCount well-formed markers with no matching passage
     */
    public record Resolution(String content,
                             List<CitedPassage> citedPassages,
                             int markerCount,
                             int invalidMarkerCount) {

        public boolean hasCitations() {
            return !citedPassages.isEmpty();
        }
    }

    public static Resolution resolve(String answer,
                                     List<RetrievedChunkRow> evidence,
                                     String noEvidenceAnswer) {
        String text = answer == null ? "" : answer;
        Set<Integer> ordinals = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(text);
        int markerCount = 0;
        int invalidMarkerCount = 0;

        while (matcher.find()) {
            markerCount++;
            int ordinal = Integer.parseInt(
                    matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            if (ordinal < 1 || ordinal > evidence.size()) {
                invalidMarkerCount++;
            } else {
                ordinals.add(ordinal);
            }
        }

        if (ordinals.isEmpty()) {
            return new Resolution(noEvidenceAnswer, List.of(), markerCount, invalidMarkerCount);
        }

        List<CitedPassage> cited = new ArrayList<>(ordinals.size());
        ordinals.stream()
                .sorted()
                .forEach(ordinal -> cited.add(
                        new CitedPassage(ordinal, evidence.get(ordinal - 1))));
        return new Resolution(text, List.copyOf(cited), markerCount, invalidMarkerCount);
    }
}
