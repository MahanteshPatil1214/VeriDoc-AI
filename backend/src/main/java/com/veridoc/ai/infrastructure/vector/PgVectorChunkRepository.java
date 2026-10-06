package com.veridoc.ai.infrastructure.vector;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.veridoc.ai.config.properties.EmbeddingProperties;
import com.veridoc.ai.document.domain.DocumentChunk;
import com.veridoc.ai.document.persistence.RetrievedChunkRow;

/**
 * Native pgvector access.
 *
 * <p>Every method here takes {@code ownerId} explicitly. There is deliberately
 * no API on this class that can read a chunk without an authorization scope,
 * which makes the "never search everything then filter afterwards" rule
 * structurally enforced rather than a matter of discipline.
 */
@Repository
public class PgVectorChunkRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final EmbeddingProperties embeddingProperties;

    public PgVectorChunkRepository(NamedParameterJdbcTemplate jdbc,
                                   EmbeddingProperties embeddingProperties) {
        this.jdbc = jdbc;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * Cosine-distance nearest-neighbour search restricted to one owner and an
     * explicit set of authorized documents.
     *
     * @param ownerId          the authenticated user; mandatory
     * @param documentIds      documents attached to the conversation; mandatory
     * @param embedding        query vector
     * @param model            embedding model filter; prevents cross-model search
     * @param embeddingVersion embedding version filter
     * @param limit            top-k
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunkRow> similaritySearch(UUID ownerId,
                                                   Collection<UUID> documentIds,
                                                   double[] embedding,
                                                   String model,
                                                   Integer embeddingVersion,
                                                   int limit) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT c.id                       AS id,
                       c.document_id              AS document_id,
                       c.version_id               AS version_id,
                       c.chunk_index              AS chunk_index,
                       c.content                  AS content,
                       c.page_number              AS page_number,
                       c.section                  AS section,
                       c.token_count              AS token_count,
                       c.content_hash             AS content_hash,
                       d.filename                 AS filename,
                       (c.embedding <=> CAST(:embedding AS vector)) AS distance
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE c.owner_id = :ownerId
                  AND c.document_id IN (:documentIds)
                  AND d.status = 'READY'
                  AND c.embedding_model = :model
                  AND c.embedding_version = :embeddingVersion
                  AND c.embedding IS NOT NULL
                ORDER BY c.embedding <=> CAST(:embedding AS vector)
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("documentIds", documentIds)
                .addValue("embedding", VectorLiteral.of(embedding))
                .addValue("model", model)
                .addValue("embeddingVersion", embeddingVersion)
                .addValue("limit", limit);

        return jdbc.query(sql, params, (rs, rowNum) -> new RetrievedChunkRow(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                (Integer) rs.getObject("page_number"),
                rs.getString("section"),
                rs.getInt("token_count"),
                rs.getString("content_hash"),
                rs.getString("filename"),
                rs.getDouble("distance")));
    }

    /**
     * Bulk-inserts chunks with their vectors. Uses a single statement so a large
     * document does not produce thousands of round trips.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertWithEmbeddings(List<DocumentChunk> chunks, List<double[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "Expected one embedding per chunk, got " + embeddings.size()
                            + " for " + chunks.size() + " chunks");
        }
        if (chunks.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO document_chunks (
                    id, document_id, version_id, owner_id, chunk_index, content,
                    page_number, section, token_count, content_hash,
                    embedding, embedding_model, embedding_version, created_at
                ) VALUES (
                    :id, :documentId, :versionId, :ownerId, :chunkIndex, :content,
                    :pageNumber, :section, :tokenCount, :contentHash,
                    CAST(:embedding AS vector), :embeddingModel, :embeddingVersion, now()
                )
                """;

        MapSqlParameterSource[] batch = new MapSqlParameterSource[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("id", chunk.getId())
                    .addValue("documentId", chunk.getDocumentId())
                    .addValue("versionId", chunk.getVersionId())
                    .addValue("ownerId", chunk.getOwnerId())
                    .addValue("chunkIndex", chunk.getChunkIndex())
                    .addValue("content", chunk.getContent())
                    .addValue("pageNumber", chunk.getPageNumber())
                    .addValue("section", chunk.getSection())
                    .addValue("tokenCount", chunk.getTokenCount())
                    .addValue("contentHash", chunk.getContentHash())
                    .addValue("embedding", VectorLiteral.of(embeddings.get(i)))
                    .addValue("embeddingModel", chunk.getEmbeddingModel())
                    .addValue("embeddingVersion", chunk.getEmbeddingVersion());
        }
        jdbc.batchUpdate(sql, batch);
    }

    /** Reports the width of the stored vectors; catches model drift at runtime. */
    @Transactional(readOnly = true)
    public Integer storedDimensions(UUID versionId) {
        String sql = """
                SELECT vector_dims(embedding)
                FROM document_chunks
                WHERE version_id = :versionId AND embedding IS NOT NULL
                LIMIT 1
                """;
        Integer dims = jdbc.queryForObject(
                sql, new MapSqlParameterSource("versionId", versionId), Integer.class);
        return dims;
    }

    public int expectedDimensions() {
        return embeddingProperties.dimensions();
    }

    /** Renders a pgvector literal. */
    public record VectorLiteral(double[] values) {

        public static String of(double[] values) {
            if (values == null) {
                throw new IllegalArgumentException("Embedding must not be null");
            }
            StringBuilder sb = new StringBuilder(values.length * 9 + 2);
            sb.append('[');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                double v = values[i];
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new IllegalArgumentException(
                            "Embedding contains a non-finite value at index " + i);
                }
                sb.append(String.format(Locale.ROOT, "%.8f", v));
            }
            sb.append(']');
            return sb.toString();
        }
    }
}