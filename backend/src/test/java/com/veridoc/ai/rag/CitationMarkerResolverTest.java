package com.veridoc.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.veridoc.ai.document.persistence.RetrievedChunkRow;

class CitationMarkerResolverTest {

    private static final String REFUSAL =
            "I couldn't find enough information in the uploaded documents to answer that.";

    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();

    private final List<RetrievedChunkRow> evidence = List.of(
            chunk(DOC_A, "contract.pdf", 1, "The notice period is thirty days."),
            chunk(DOC_B, "policy.pdf", 7, "Retention is twelve months."));

    @Test
    @DisplayName("valid and duplicate markers resolve to the exact cited passages and original ordinals")
    void validMarkersResolveToExactEvidence() {
        String answer = "The notice period is thirty days [2]. Retention is twelve months [1]. "
                + "Notice is thirty days [2].";

        CitationMarkerResolver.Resolution result =
                CitationMarkerResolver.resolve(answer, evidence, REFUSAL);

        assertThat(result.content()).isEqualTo(answer);
        assertThat(result.hasCitations()).isTrue();
        assertThat(result.markerCount()).isEqualTo(3);
        assertThat(result.invalidMarkerCount()).isZero();
        assertThat(result.citedPassages())
                .extracting(CitationMarkerResolver.CitedPassage::ordinal)
                .containsExactly(1, 2);
        assertThat(result.citedPassages().get(0).passage().documentId()).isEqualTo(DOC_A);
        assertThat(result.citedPassages().get(0).passage().filename()).isEqualTo("contract.pdf");
        assertThat(result.citedPassages().get(0).passage().pageNumber()).isEqualTo(1);
        assertThat(result.citedPassages().get(1).passage().documentId()).isEqualTo(DOC_B);
        assertThat(result.citedPassages().get(1).passage().pageNumber()).isEqualTo(7);
    }

    @Test
    @DisplayName("full-width markers are accepted but malformed and nested markers are not")
    void markerSyntaxIsStrict() {
        CitationMarkerResolver.Resolution fullWidth =
                CitationMarkerResolver.resolve("Retention is twelve months 【2】.",
                        evidence, REFUSAL);
        assertThat(fullWidth.citedPassages())
                .extracting(CitationMarkerResolver.CitedPassage::ordinal)
                .containsExactly(2);

        for (String malformed : List.of("[1,2]", "[1-2]", "[[1]]", "[ 1 ]")) {
            CitationMarkerResolver.Resolution result =
                    CitationMarkerResolver.resolve(malformed, evidence, REFUSAL);
            assertThat(result.hasCitations()).as(malformed).isFalse();
            assertThat(result.content()).as(malformed).isEqualTo(REFUSAL);
        }
    }

    @Test
    @DisplayName("a missing marker is replaced by the refusal and persists no citation")
    void missingMarkerFailsClosed() {
        CitationMarkerResolver.Resolution result = CitationMarkerResolver.resolve(
                "A confident answer with no source.", evidence, REFUSAL);

        assertThat(result.content()).isEqualTo(REFUSAL);
        assertThat(result.hasCitations()).isFalse();
        assertThat(result.markerCount()).isZero();
        assertThat(result.invalidMarkerCount()).isZero();
    }

    @Test
    @DisplayName("out-of-range markers are ignored and never become fabricated sources")
    void outOfRangeMarkersFailClosed() {
        CitationMarkerResolver.Resolution result = CitationMarkerResolver.resolve(
                "Claim [0]. Claim [3]. Claim [99].", evidence, REFUSAL);

        assertThat(result.content()).isEqualTo(REFUSAL);
        assertThat(result.hasCitations()).isFalse();
        assertThat(result.markerCount()).isEqualTo(3);
        assertThat(result.invalidMarkerCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("valid citations survive alongside invalid markers, which remain literal")
    void mixedMarkersKeepOnlyValidSources() {
        String answer = "The notice period is thirty days [1]. Unsupported detail [99].";

        CitationMarkerResolver.Resolution result =
                CitationMarkerResolver.resolve(answer, evidence, REFUSAL);

        assertThat(result.content()).isEqualTo(answer);
        assertThat(result.citedPassages())
                .extracting(CitationMarkerResolver.CitedPassage::ordinal)
                .containsExactly(1);
        assertThat(result.invalidMarkerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("null or blank answers fail closed")
    void emptyAnswersFailClosed() {
        assertThat(CitationMarkerResolver.resolve(null, evidence, REFUSAL).content())
                .isEqualTo(REFUSAL);
        assertThat(CitationMarkerResolver.resolve("   ", evidence, REFUSAL).content())
                .isEqualTo(REFUSAL);
    }

    @Test
    @DisplayName("structural validation does not claim that a cited claim is semantically supported")
    void structuralValidationDoesNotProveSemanticSupport() {
        // The cited passage is about notice periods; the sentence is not. The
        // resolver can prove provenance, not entailment. A semantic verifier is a
        // separate concern and is deliberately not claimed here.
        String unsupported = "The moon is made of cheese [1].";

        CitationMarkerResolver.Resolution result =
                CitationMarkerResolver.resolve(unsupported, evidence, REFUSAL);

        assertThat(result.content()).isEqualTo(unsupported);
        assertThat(result.citedPassages())
                .extracting(CitationMarkerResolver.CitedPassage::ordinal)
                .containsExactly(1);
    }

    private static RetrievedChunkRow chunk(UUID documentId, String filename, int page,
                                           String content) {
        return new RetrievedChunkRow(
                UUID.randomUUID(), documentId, UUID.randomUUID(), 0, content, page,
                null, 6, "hash", filename, 0.1d);
    }
}
