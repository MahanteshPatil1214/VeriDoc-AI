# VeriDoc AI — Architecture

Evidence-grounded RAG: answers must be supported by the user's uploaded
documents, are generated with Retrieval-Augmented Generation, and carry
verifiable citations. When no supporting evidence is found the system refuses
rather than inventing an answer.

## System overview

```
                          ┌──────────────────────────────────────────┐
                          │            Browser (SPA)                │
                          │  React 19 · TanStack Query · SSE client │
                          └─────────────────┬────────────────────────┘
                                            │  /api (same origin)
                          ┌─────────────────▼────────────────────────┐
                          │  nginx (frontend container)             │
                          │  static SPA · try_files fallback        │
                          │  /api → backend:8080 (SSE unbuffered)   │
                          └─────────────────┬────────────────────────┘
                          ┌─────────────────▼────────────────────────┐
                          │  Spring Boot backend (Java 21)          │
                          │  auth · documents · conversations · rag │
                          └──────┬───────────────────────┬───────────┘
                          ┌──────▼────────┐     ┌───────▼──────────┐
                          │ PostgreSQL 17 │     │ Redis 7          │
                          │  + pgvector   │     │ rate-limit        │
                          │  (source of   │     │ counters only     │
                          │   truth)      │     └───────────────────┘
                          └──────┬────────┘
                                 │ embeddings ·
                          ┌──────▼────────┐
                          │ Google Gemini │
                          │ chat + embed  │
                          └───────────────┘
```

Two independent "sides" meet in the RAG service:

1. **Ingestion** turns uploaded files into indexed vectors (asynchronously).
2. **Conversations** attach indexed documents and answer questions over their
   chunks.

Both are relentlessly **owner-scoped**: every query filters by the authenticated
user, so user A can never read user B's documents, chunks, or threads.

## Components

### Frontend (`frontend/`)

- Vite + React 19 + TypeScript single-page app.
- Server state via TanStack Query; UI state (active conversation, drawer,
  sidebar) via Zustand.
- **SSE client** (`lib/sse.ts`): the chat stream uses `GET` with an
  `Authorization` header, so it is consumed with `fetch` + manual parsing
  rather than `EventSource`. Events: `token*`, `citations`, `done`, `error`.
- Hand-rolled Markdown renderer (`components/Markdown.tsx`) — no markdown
  dependency; the assistant's prose is rendered as React nodes with a
  streaming caret.
- Documents drawer: drag-drop upload, live status polling (2 s while
  processing), attach-to-conversation checkboxes, retry/delete per document.
- Sidebar: conversation create/open/rename/delete, responsive off-canvas.

### Backend (`backend/`)

Spring Boot 4.1 monolith. Packages by aggregate:

- `auth` — registration, login, refresh, `/me`. JWT (access + rotating refresh).
- `document` — upload, listing, status, delete, retry; the ingestion pipeline.
- `conversation` — threads, attach/detach, message history, rename, delete.
- `rag` — retrieval, prompt assembly, Gemini calls, citations, admin debugger.
- `security` — JWT filter, current-user resolution, rate limiting.
- `monitoring` — actuator health/readiness/metrics.
- `common` — error taxonomy (`ErrorCode`) and `AppException`.

### Data stores

- **PostgreSQL 17 + pgvector** is the source of truth: users, documents,
  versions, chunks (with `vector(768)` embeddings), conversations, messages,
  citations, feedback. Flyway manages schema.
- **Redis** holds only rate-limit counters (`noeviction`, no persistence).

### Google Gemini

- Chat model: `gemini-2.5-flash` for grounded answers.
- Embedding model: `text-embedding-004`, 768 dimensions, batch-embedded.
- The API key lives only in backend environment variables.

## Document ingestion pipeline

On upload the document is stored and processed asynchronously. Public status:

```
UPLOADING → PROCESSING → EXTRACTING → CHUNKING → EMBEDDING → INDEXING → READY
                                                                       └→ FAILED
                                                    (any processing step can fail)
```

- **Extraction** (PDFBox) reads text; encrypted, empty, or unreadable PDFs fail
  with a specific `ErrorCode` and message shown in the UI.
- **Chunking** splits text with token-aware sizing (`CHUNK_MAX_TOKENS`,
  `CHUNK_OVERLAP_TOKENS`); every chunk stores the page and section for citation.
- **Embedding** batches chunks to the Gemini embedding model; the vector width
  must match the migration (`GEMINI_EMBEDDING_DIMENSIONS=768`,
  `EMBEDDING_VERSION=1`).
- **Indexing** writes chunks under a new `document_version` and marks the
  document `READY`.

State machine facts that matter:

- A `FAILED` document can be **retried** (`POST /documents/{id}/retry`); the
  guard accepts only `FAILED`, returning `409 CONFLICT` otherwise.
- A document can be **deleted** (`DELETE /documents/{id}`) at any time —
  including mid-processing. Delete is a soft delete (`status = DELETED`):
  derived chunks and versions are removed by an application event listener, the
  stored file is removed, and `status = READY` rows no longer match retrieval,
  so the deleted document stops being queried and re-uploading the same bytes is
  allowed again.
- Async pipeline callbacks are guarded: `markReady`/`markFailed` are no-ops once
  a document is `DELETED`, so a late pipeline step cannot resurrect a deleted
  document.

## Retrieval & answer generation

1. Messages are re-written when `RAG_ENABLE_QUERY_REWRITING=true` so multi-turn
   questions are grounded without chaining prior LLM output.
2. **Retrieval SQL** runs a pgvector `<=>` (cosine distance) search over
   chunks of documents that are `status = 'READY'`, attached to the
   conversation, and owned by the user. Scores below `RETRIEVAL_MIN_SCORE` are
   dropped.
3. The prompt is assembled from retrieved chunks (untrusted data) and history,
   bounded by `RAG_MAX_CONTEXT_TOKENS`.
4. Gemini answers; only chunks actually cited are emitted as citations.

If no chunk clears the score threshold, or the model cannot ground an answer in
the context, the reply is `RagProperties.noEvidenceAnswer()` — the explicit no-
evidence refusal. Assistant prose is treated as untrusted: retrieved text is
evidence, never instructions.

Streaming (`GET /conversations/{id}/stream?message=...`) emits `token*` events,
one `citations` event, then a terminal `done` event (with the persisted reply).
Failures arrive as a terminal `error` event with a stable `code`.

## Security

- **JWT**: access token (`JWT_ACCESS_TTL`), rotating refresh
  (`JWT_REFRESH_TTL`), fixed issuer; secret from environment.
- **Ownership**: every document/conversation service method resolves the row
  with `findBy…AndOwner`; foreign resources surface as `404 NOT FOUND` (no
  existence leakage).
- **Files**: upload size and page caps (20 MiB, 500 pages), extension +
  content sniffing, random storage names under `STORAGE_ROOT`, never served back.
- **Rate limits** (Redis-backed, `RATE_LIMIT_ENABLED`): login, upload, chat,
  processing buckets.
- **Error taxonomy**: consistent `{code, message, details, timestamp}` JSON on
  every failure; stable `ErrorCode` strings and HTTP statuses.

## Data model (core)

- `users`, `documents`, `document_versions`, `document_chunks`
  (`embedding vector(768)`, page, section).
- `conversations`, `conversation_documents`, `messages`.
- `citations` and `feedback` deliberately have **no foreign key to documents**:
  conversation history and the evaluation trail survive document deletion.
  Messages, citations and conversation-document links cascade with the
  conversation.
- Chunks/versions cascade from documents; the delete listener prunes them
  explicitly so vector data never outlives its document.

## Testing strategy

- **Integration tests** (`*IT.java`, Testcontainers) boot the real application
  against a throwaway pgvector container (`AbstractPostgresIntegrationTest`)
  and drive it through `MockMvc` with a deterministic Gemini stub
  (`IngestionTestConfig` + `TestPdfs`).
- Boundaries covered: auth, ownership isolation, upload/file-security,
  document lifecycle (delete/retry), chat + streaming contract, prompt
  injection, query rewriting, RAG debug gating, actuator exposure, rate limits,
  and a RAG evaluation gate that asserts grounded answers score above a
  threshold.

## Cross-cutting choices

- **No client-side secrets**: the Gemini key is backend-only.
- **NGINX eliminates CORS**: the SPA talks to `/api` on its own origin.
- **Deterministic config**: env-driven (`RagProperties`, generation props), no
  per-deploy code edits.
- **Builds are reproducible**: multi-stage Dockerfiles with pinned base images;
  the backend build runs on the host network to avoid BuildKit DNS issues.