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

    public record CorpusDocument(String filename, String text) {
    }
}
