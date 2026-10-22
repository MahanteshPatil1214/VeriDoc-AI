package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * One graded retrieval/answer scenario.
 *
 * <p>Fields are additive on top of the original five-field shape:
 * <ul>
 *   <li>{@code id} and {@code category} identify and classify the case;
 *       {@code category} drives both validation and the reported breakdown;</li>
 *   <li>{@code expectedDocument} is the legacy single-document label and is
 *       still honoured when {@code expectedDocuments} is empty;</li>
 *   <li>{@code expectedDocuments} lists one or more expected sources;</li>
 *   <li>{@code expectedPages} narrows relevance to specific 1-based pages of a
 *       single expected document;</li>
 *   <li>{@code expectedClaims} are independent, human-checkable answer claims
 *       (not yet machine-scored — see Stage 3);</li>
 *   <li>{@code expectRefusal} plus {@code nearMiss} describe the abstention
 *       contract and the plausible-but-insufficient document;</li>
 *   <li>{@code history} holds prior user turns for multi-turn cases;</li>
 *   <li>{@code adversarial} marks prompt-injection cases, and
 *       {@code injectionPhrases} lists the directives the answer must not
 *       contain.</li>
 * </ul>
 *
 * <p>Chunk ids are intentionally absent: they are random per ingest and can
 * never be authored as expectations. Page is the finest stable label.
 */
public record EvalCase(
        String id,
        String category,
        String question,
        String expectedDocument,
        List<String> expectedDocuments,
        List<Integer> expectedPages,
        List<String> expectedKeywords,
        List<String> expectedClaims,
        boolean expectRefusal,
        String nearMiss,
        List<HistoryTurn> history,
        boolean adversarial,
        List<String> injectionPhrases
) {

    public EvalCase {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
        expectedPages = expectedPages == null ? List.of() : List.copyOf(expectedPages);
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        expectedClaims = expectedClaims == null ? List.of() : List.copyOf(expectedClaims);
        history = history == null ? List.of() : List.copyOf(history);
        injectionPhrases = injectionPhrases == null ? List.of() : List.copyOf(injectionPhrases);
    }

    public boolean isGrounded() {
        return !expectRefusal;
    }

    /** Effective relevance labels: {@code expectedDocuments} wins, legacy field is the fallback. */
    public RelevanceLabels labels() {
        return RelevanceLabels.of(resolvedDocuments(), expectedPages);
    }

    /** The expected documents in declaration order, resolving the legacy single-document field. */
    public List<String> resolvedDocuments() {
        if (!expectedDocuments.isEmpty()) {
            return expectedDocuments;
        }
        return expectedDocument == null ? List.of() : List.of(expectedDocument);
    }
}
