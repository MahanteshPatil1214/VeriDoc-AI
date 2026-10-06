-- =====================================================================
-- VeriDoc AI :: Initial schema
-- PostgreSQL 17 + pgvector
--
-- Design notes:
--  * All identifiers are application-generated UUIDs (no DB sequences needed).
--  * document_chunks carries a denormalised owner_id so that authorization
--    filtering and vector similarity search happen inside a SINGLE SQL
--    statement. We never "search everything then filter".
--  * citations / feedback reference document_id and chunk_id WITHOUT foreign
--    keys on purpose: the evidence trail must survive document deletion.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Case-insensitive uniqueness without requiring the citext extension.
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));

-- ---------------------------------------------------------------------
-- documents
-- ---------------------------------------------------------------------
CREATE TABLE documents (
    id               UUID          PRIMARY KEY,
    owner_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    filename         VARCHAR(512)  NOT NULL,
    content_type     VARCHAR(128)  NOT NULL,
    size_bytes       BIGINT        NOT NULL,
    sha256           VARCHAR(64)      NOT NULL,
    storage_path     VARCHAR(1024) NOT NULL,
    status           VARCHAR(32)   NOT NULL,
    processing_stage VARCHAR(32),
    processing_error VARCHAR(2000),
    page_count       INTEGER,
    chunk_count      INTEGER,
    version          INTEGER       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_documents_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_documents_status CHECK (
        status IN ('UPLOADING', 'PROCESSING', 'EXTRACTING', 'CHUNKING',
                   'EMBEDDING', 'INDEXING', 'READY', 'FAILED', 'DELETED')
    )
);

CREATE INDEX ix_documents_owner_created ON documents (owner_id, created_at DESC);
CREATE INDEX ix_documents_owner_status ON documents (owner_id, status);

-- User-aware duplicate detection: the same bytes uploaded by two different
-- users are two independent documents.
CREATE UNIQUE INDEX ux_documents_owner_sha256
    ON documents (owner_id, sha256)
    WHERE status <> 'DELETED';

-- ---------------------------------------------------------------------
-- document_versions
-- ---------------------------------------------------------------------
CREATE TABLE document_versions (
    id               UUID         PRIMARY KEY,
    document_id      UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version          INTEGER      NOT NULL,
    file_hash        VARCHAR(64)     NOT NULL,
    embedding_model  VARCHAR(128),
    embedding_version INTEGER,
    chunk_count      INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_document_versions UNIQUE (document_id, version)
);

-- ---------------------------------------------------------------------
-- document_chunks  (the vector table)
-- ---------------------------------------------------------------------
CREATE TABLE document_chunks (
    id                UUID         PRIMARY KEY,
    document_id       UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id        UUID         NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    owner_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    chunk_index       INTEGER      NOT NULL,
    content           TEXT         NOT NULL,
    page_number       INTEGER,
    section           VARCHAR(512),
    token_count       INTEGER      NOT NULL,
    content_hash      VARCHAR(64)     NOT NULL,
    embedding         vector(768),
    embedding_model   VARCHAR(128),
    embedding_version INTEGER,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_document_chunks_chunk UNIQUE (version_id, chunk_index),
    CONSTRAINT ck_document_chunks_page CHECK (page_number IS NULL OR page_number > 0)
);

-- Drives the "authorized vector search" WHERE clause.
CREATE INDEX ix_document_chunks_owner ON document_chunks (owner_id);
CREATE INDEX ix_document_chunks_document ON document_chunks (document_id);
CREATE INDEX ix_document_chunks_version ON document_chunks (version_id);

-- Approximate nearest-neighbour index. Retrieval always combines this with an
-- authorization predicate on owner_id / document_id.
CREATE INDEX ix_document_chunks_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- Hard guarantee: never mix incompatible embedding spaces.
-- (Enforced in the application; this index supports the maintenance queries.)
CREATE INDEX ix_document_chunks_embedding_meta
    ON document_chunks (embedding_model, embedding_version);

-- ---------------------------------------------------------------------
-- conversations
-- ---------------------------------------------------------------------
CREATE TABLE conversations (
    id         UUID         PRIMARY KEY,
    owner_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(255) NOT NULL DEFAULT 'New chat',
    lock_version BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_conversations_owner_updated ON conversations (owner_id, updated_at DESC);

CREATE TABLE conversation_documents (
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    document_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, document_id)
);

CREATE INDEX ix_conversation_documents_document ON conversation_documents (document_id);

-- ---------------------------------------------------------------------
-- messages
-- ---------------------------------------------------------------------
CREATE TABLE messages (
    id                UUID         PRIMARY KEY,
    conversation_id   UUID         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role              VARCHAR(16)  NOT NULL,
    content           TEXT         NOT NULL DEFAULT '',
    status            VARCHAR(24)  NOT NULL,
    model             VARCHAR(128),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    latency_ms        BIGINT,
    error_code        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_messages_status CHECK (status IN ('COMPLETE', 'STREAMING', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_messages_conversation_created ON messages (conversation_id, created_at);

-- ---------------------------------------------------------------------
-- citations  (evidence trail - survives document deletion by design)
-- ---------------------------------------------------------------------
CREATE TABLE citations (
    id                UUID            PRIMARY KEY,
    message_id        UUID            NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    ordinal           INTEGER         NOT NULL,
    document_id       UUID            NOT NULL,
    document_filename VARCHAR(512)    NOT NULL,
    chunk_id          UUID            NOT NULL,
    page_number       INTEGER,
    section           VARCHAR(512),
    relevance_score   DOUBLE PRECISION,
    quoted_text       TEXT,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT ux_citations_message_ordinal UNIQUE (message_id, ordinal)
);

CREATE INDEX ix_citations_message ON citations (message_id);
CREATE INDEX ix_citations_document ON citations (document_id);

-- ---------------------------------------------------------------------
-- feedback
-- ---------------------------------------------------------------------
CREATE TABLE feedback (
    id         UUID         PRIMARY KEY,
    message_id UUID         NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating     VARCHAR(16)  NOT NULL,
    comment    VARCHAR(2000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_feedback_message_user UNIQUE (message_id, user_id),
    CONSTRAINT ck_feedback_rating CHECK (rating IN ('UP', 'DOWN'))
);

CREATE INDEX ix_feedback_message ON feedback (message_id);