package com.veridoc.ai.evaluation.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.veridoc.ai.evaluation.EvalCase;
import com.veridoc.ai.evaluation.EvalDataset;

/**
 * Deterministic selection of 10 representative cases from the 42-case dataset.
 */
public final class LiveEvalCases {

    public static final int EXPECTED_CORPUS_DOCUMENTS = 13;
    public static final int REQUIRED_CASES = 10;

    /**
     * Pinned case ids covering all categories: direct, refusal, multi-page,
     * multi-document, exact-value, multi-turn, ambiguous, prompt-injection.
     */
    public static final List<String> CASE_IDS = List.of(
            "direct-vacation-approval",
            "direct-flight-class",
            "refusal-career-break",
            "refusal-diwali",
            "page-security-hotline",
            "multi-onboarding-procurement",
            "exact-contract-id",
            "turn-vacation-carryover",
            "ambiguous-vacation-five-years",
            "injection-shared-accounts");

    private LiveEvalCases() {
    }

    public static EvalDataset select(EvalDataset full) {
        return select(full, REQUIRED_CASES);
    }

    public static EvalDataset select(EvalDataset full, int maxCases) {
        if (full == null) {
            throw new IllegalArgumentException("full dataset must not be null");
        }
        if (full.corpus() == null || full.corpus().size() != EXPECTED_CORPUS_DOCUMENTS) {
            throw new IllegalArgumentException("live evaluation requires the full " + EXPECTED_CORPUS_DOCUMENTS
                    + "-document corpus");
        }
        if (CASE_IDS.size() != REQUIRED_CASES) {
            throw new IllegalArgumentException("case selection must be exactly " + REQUIRED_CASES + " cases");
        }
        if (maxCases < REQUIRED_CASES) {
            throw new IllegalArgumentException("the live evaluation requires at least " + REQUIRED_CASES
                    + " cases, budget allows " + maxCases);
        }
        validateUnique(CASE_IDS);

        Map<String, EvalCase> byId = new LinkedHashMap<>(full.cases().size());
        for (EvalCase c : full.cases()) {
            byId.put(c.id(), c);
        }
        List<EvalCase> selected = new ArrayList<>(CASE_IDS.size());
        for (String id : CASE_IDS) {
            EvalCase c = byId.get(id);
            if (c == null) {
                throw new IllegalArgumentException("missing live evaluation case: " + id);
            }
            selected.add(c);
        }
        validateCoverage(selected);

        return new EvalDataset(full.name() + "-live-10", List.copyOf(full.corpus()), List.copyOf(selected));
    }

    private static void validateUnique(List<String> ids) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate live evaluation case id: " + id);
            }
        }
    }

    private static void validateCoverage(List<EvalCase> selected) {
        Set<String> categories = new LinkedHashSet<>();
        boolean refusal = false;
        boolean multiTurn = false;
        boolean pageLabeled = false;
        boolean grounded = false;
        for (EvalCase c : selected) {
            categories.add(c.category());
            if (c.expectRefusal()) {
                refusal = true;
            }
            if (c.history() != null && !c.history().isEmpty()) {
                multiTurn = true;
            }
            if (c.expectedPages() != null && !c.expectedPages().isEmpty()) {
                pageLabeled = true;
            }
            if (!c.expectRefusal()) {
                grounded = true;
            }
        }
        if (categories.size() != 8) {
            throw new IllegalArgumentException("live selection must cover 8 categories");
        }
        if (!refusal) {
            throw new IllegalArgumentException("live selection must include a refusal case");
        }
        if (!multiTurn) {
            throw new IllegalArgumentException("live selection must include a multi-turn case");
        }
        if (!pageLabeled) {
            throw new IllegalArgumentException("live selection must include a page-labelled case");
        }
        if (!grounded) {
            throw new IllegalArgumentException("live selection must include a grounded case");
        }
    }
}
