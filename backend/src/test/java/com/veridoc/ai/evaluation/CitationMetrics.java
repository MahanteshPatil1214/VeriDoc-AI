package com.veridoc.ai.evaluation;

import java.util.List;

/**
 * Document-level precision over the passages the model actually cited.
 *
 * <p>This is a provenance check, not a faithfulness check. It answers "did the
 * model cite a document the case says is relevant", never "does the cited
 * passage entail the sentence it is attached to". Structural citation validity
 * must not be reported as semantic support.
 *
 * <p>Page-level correctness is a <em>second</em>, coarser question than the
 * document-level one above, and it is reported separately rather than
 * replacing it:
 * <ul>
 *   <li>document precision answers "did the model cite a document the case
 *       says is relevant";</li>
 *   <li>page precision answers "given that it cited the right document, did it
 *       cite the right page of it".</li>
 * </ul>
 *
 * <p>Both remain provenance checks. Neither proves that the answer's prose is
 * supported by the passage it points at.
 */
public final class CitationMetrics {

    private CitationMetrics() {
    }

    /**
     * {@code citationPrecision = |cited passages whose document is labelled| / |cited|}.
     *
     * <p>Empty-input policy — every vacuous case is {@code 0.0}, never a free
     * {@code 1.0}, so an abstention or a missing expectation cannot silently
     * satisfy a precision gate:
     * <ul>
     *   <li>no citations and no labels (an abstention case) — nothing to
     *       measure; callers exclude ungrounded cases from the aggregate;</li>
     *   <li>no citations but labels exist (a grounded case that answered
     *       without evidence) — {@code 0.0};</li>
     *   <li>citations but no labels (cited something when nothing was
     *       expected) — {@code 0.0};</li>
     *   <li>no citations and no labels — {@code 0.0}.</li>
     * </ul>
     */
    public static double citationPrecision(List<EvidencePassage> citations,
                                           RelevanceLabels labels) {
        if (citations == null || citations.isEmpty() || labels == null || labels.isEmpty()) {
            return 0.0;
        }
        long correct = 0;
        for (EvidencePassage citation : citations) {
            if (labels.matchesDocument(citation)) {
                correct++;
            }
        }
        return (double) correct / citations.size();
    }

    // =====================================================================
    // Page-level citation metrics
    // =====================================================================

    /**
     * Whether a case declares page-level expectations at all.
     *
     * <p>True only when the case names at least one expected document
     * <em>and</em> at least one expected page. A case that labels a document
     * but no page is graded by the document-level metric only: the page metrics
     * have nothing to measure for it, so it is <strong>not applicable</strong>
     * and must be excluded from their aggregates rather than counted as a
     * failure.
     *
     * <p>Callers use this to decide which cases enter a page aggregate; the
     * metric functions themselves return {@code 0.0} for a non-applicable case
     * so that no caller can accidentally treat "nothing to measure" as a
     * perfect score.
     */
    public static boolean pageLabelsApplicable(RelevanceLabels labels) {
        return labels != null && !labels.isEmpty() && !labels.pages().isEmpty();
    }

    /**
     * Page-level citation precision.
     *
     * <pre>
     * pageCitationPrecision =
     *     |citations to an expected document whose page is labelled|
     *     --------------------------------------------------------
     *     |citations to an expected document|
     * </pre>
     *
     * <p>The denominator deliberately counts only citations that point at an
     * <em>expected document</em>. A citation to an unexpected document is
     * already wrong, and is already counted by
     * {@link #citationPrecision(List, RelevanceLabels)}; counting it here as
     * well would charge one mistake twice and would make the page metric a
     * worse copy of the document metric than it needs to be. The two metrics
     * are reported side by side, so a case that cites the wrong document is
     * still visible.
     *
     * <p>Edge cases — no vacuous {@code 1.0}, ever:
     * <ul>
     *   <li>the case declares no page expectation — not applicable, excluded
     *       from aggregates by {@link #pageLabelsApplicable(RelevanceLabels)},
     *       and {@code 0.0} here so a non-applicable case can never look
     *       perfect;</li>
     *   <li>the case expects pages but cited nothing — {@code 0.0};</li>
     *   <li>the case expects pages but cited nothing from an expected
     *       document — {@code 0.0}; the expectation was declared and not met;</li>
     *   <li>a citation to the expected document but an unlabelled page — counts
     *       in the denominator and not the numerator: it is precisely the
     *       mistake this metric exists to catch;</li>
     *   <li>a citation with no page number at all — counts in the denominator
     *       and not the numerator. A citation that cannot be shown to be on the
     *       right page must not score as correct, because that would reward
     *       missing metadata. {@link #missingPageCitationCount(List,
     *       RelevanceLabels)} separates this case from a genuinely wrong page;</li>
     *   <li>two citations of the same page — two citation items, so they count
     *       twice, exactly as two relevant chunks of one page count twice in
     *       {@code RetrievalMetrics}.</li>
     * </ul>
     */
    public static double pageCitationPrecision(List<EvidencePassage> citations,
                                                RelevanceLabels labels) {
        if (!pageLabelsApplicable(labels)) {
            return 0.0;
        }
        if (citations == null || citations.isEmpty()) {
            return 0.0;
        }
        long denominator = 0;
        long correct = 0;
        for (EvidencePassage citation : citations) {
            if (!labels.matchesDocument(citation)) {
                continue;
            }
            denominator++;
            if (labels.matches(citation)) {
                correct++;
            }
        }
        if (denominator == 0) {
            return 0.0;
        }
        return (double) correct / denominator;
    }

    /**
     * Page-level citation recall: the fraction of the labelled page targets the
     * model actually cited.
     *
     * <pre>
     * pageCitationRecall =
     *     |labelled (document, page) targets cited at least once|
     *     ------------------------------------------------------
     *     |labelled (document, page) targets|
     * </pre>
     *
     * <p>Recall answers a question precision cannot: a case that labelled two
     * pages and cited one of them perfectly scores {@code 1.0} precision and
     * {@code 0.5} recall. Precision alone would hide the missed page.
     *
     * <p>A target counts as met when at least one citation names that document
     * and that page. Edge cases mirror {@link #pageCitationPrecision}:
     * <ul>
     *   <li>no page expectation — not applicable, {@code 0.0} here, excluded
     *       from aggregates by {@link #pageLabelsApplicable(RelevanceLabels)};</li>
     *   <li>no citations — {@code 0.0};</li>
     *   <li>citations that miss every labelled page — {@code 0.0};</li>
     *   <li>a citation with no page number never satisfies a page target.</li>
     * </ul>
     *
     * <p>Page labels are validated to apply to exactly one expected document,
     * so a target set is one target per labelled page.
     */
    public static double pageCitationRecall(List<EvidencePassage> citations,
                                            RelevanceLabels labels) {
        if (!pageLabelsApplicable(labels)) {
            return 0.0;
        }
        List<RelevanceLabels.RelevanceTarget> targets = labels.targets();
        if (targets.isEmpty()) {
            return 0.0;
        }
        if (citations == null || citations.isEmpty()) {
            return 0.0;
        }
        long matched = 0;
        for (RelevanceLabels.RelevanceTarget target : targets) {
            for (EvidencePassage citation : citations) {
                if (target.matches(citation)) {
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / targets.size();
    }

    /**
     * Diagnostic: how many citations to an expected document carry no page
     * number.
     *
     * <p>Such a citation is scored as <em>incorrect</em> by
     * {@link #pageCitationPrecision(List, RelevanceLabels)} — a citation that
     * cannot be shown to be on the right page must not score as correct. This
     * counter exists so that "the model cited the wrong page" and "the chunk
     * carries no page metadata" stay distinguishable instead of collapsing into
     * the same failure.
     *
     * <p>Returns {@code 0} for a case with no page expectations: such a case has
     * nothing to compare a page number against.
     */
    public static long missingPageCitationCount(List<EvidencePassage> citations,
                                                 RelevanceLabels labels) {
        if (!pageLabelsApplicable(labels)) {
            return 0L;
        }
        if (citations == null || citations.isEmpty()) {
            return 0L;
        }
        long missing = 0;
        for (EvidencePassage citation : citations) {
            if (labels.matchesDocument(citation) && citation.pageNumber() == null) {
                missing++;
            }
        }
        return missing;
    }
}
