-- =====================================================================
-- VeriDoc AI :: Hybrid search (BM25 + vector)
-- PostgreSQL 17 + pgvector
--
-- Design notes:
--  * Adds a tsvector column to document_chunks for full-text (BM25-style)
--    keyword search, complementing the existing pgvector cosine search.
--  * A trigger keeps search_vector in sync with content on every insert/update.
--  * A GIN index makes keyword lookups fast.
--  * Both searches remain scoped by owner_id + document_id inside a single
--    SQL statement — no "search everything then filter" path.
-- =====================================================================

-- pg_trgm improves prefix/fuzzy matching for keyword queries.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- Full-text search column on document_chunks
-- ---------------------------------------------------------------------
ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- GIN index drives fast tsvector @@ tsquery lookups.
CREATE INDEX IF NOT EXISTS ix_document_chunks_search_vector
    ON document_chunks USING gin(search_vector);

-- ---------------------------------------------------------------------
-- Trigger: keep search_vector in sync with content
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_chunks_search_vector_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.content, '')), 'A');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvectorupdate
    BEFORE INSERT OR UPDATE OF content ON document_chunks
    FOR EACH ROW EXECUTE FUNCTION document_chunks_search_vector_trigger();

-- ---------------------------------------------------------------------
-- Backfill existing chunks so keyword search works immediately.
-- ---------------------------------------------------------------------
UPDATE document_chunks
SET search_vector = setweight(to_tsvector('english', COALESCE(content, '')), 'A')
WHERE search_vector IS NULL;

-- ---------------------------------------------------------------------
-- Note: the HNSW vector index (ix_document_chunks_hnsw) created in V1
-- continues to serve cosine nearest-neighbour search. Hybrid retrieval
-- combines both indexes in the application layer.
-- =====================================================================
