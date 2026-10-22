package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * What a single case run produced.
 *
 * <p>{@code retrieved} and {@code citations} are deliberately separate and must
 * never be substituted for one another:
 * <ul>
 *   <li>{@code retrieved} is the evidence that was actually passed to the model,
 *       in retrieval order (best first). Retrieval metrics consume only this.</li>
 *   <li>{@code citations} is the set of passages the model actually cited.
 *       Citation metrics consume only this.</li>
 * </ul>
 *
 * <p>A model can cite a document retrieval never returned (a hallucinated
 * source is rejected by the citation resolver, but a *real* document that was
 * not retrieved cannot be cited at all), and it can cite nothing from evidence
 * that was retrieved. Collapsing the two lists is what made the previous
 * metrics unable to tell those cases apart.
 */
public record EvalObservation(
        List<EvidencePassage> retrieved,
        List<EvidencePassage> citations,
        boolean refusal,
        String answer
) {

    public EvalObservation {
        retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
        citations = citations == null ? List.of() : List.copyOf(citations);
        answer = answer == null ? "" : answer;
    }

    /** A run that retrieved nothing and cited nothing. */
    public static EvalObservation refused(String answer) {
        return new EvalObservation(List.of(), List.of(), true, answer);
    }

    /** Convenience projection for readable assertions and report lines. */
    public List<String> retrievedDocuments() {
        return retrieved.stream().map(EvidencePassage::documentFilename).toList();
    }

    /** Convenience projection for readable assertions and report lines. */
    public List<String> citedDocuments() {
        return citations.stream().map(EvidencePassage::documentFilename).toList();
    }
}
