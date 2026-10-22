package com.veridoc.ai.evaluation;

import java.util.UUID;

/**
 * One piece of evidence, either retrieved or cited.
 *
 * <p>{@code documentFilename} and {@code pageNumber} are the labels an
 * evaluation case can refer to by hand. {@code chunkId} is the exact ingested
 * row and is used for identity only: it is generated at ingest time, so it can
 * never be written into the dataset as an expectation.
 *
 * <p>Deliberately carries no text. Evaluation reports and failures must not
 * echo document contents into logs.
 */
public record EvidencePassage(
        String documentFilename,
        UUID chunkId,
        Integer pageNumber
) {
}
