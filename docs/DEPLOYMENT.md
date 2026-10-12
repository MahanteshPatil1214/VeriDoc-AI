# VeriDoc AI — Deployment

## Topology

```
docker compose up -d --build
```

| Service  | Image / build             | Port (host) | Purpose |
| -------- | ------------------------- | ----------- | ------- |
| `postgres` | `pgvector/pgvector:pg17` | `${DATABASE_PORT:-5432}` | Source of truth + embeddings |
| `redis`  | `redis:7-alpine`          | `${REDIS_PORT:-6379}`  | Rate-limit counters |
| `backend` | `backend/Dockerfile` (multi-stage) | `${SERVER_PORT:-8080}` | API + ingestion + RAG |
| `frontend` | `frontend/Dockerfile` (node→nginx) | `${FRONTEND_PORT:-8081}` | SPA + `/api` proxy |

A bare `cp .env.example .env && docker compose up -d --build` brings the whole
stack up; open <http://localhost:8081>.

## Prerequisites

- Docker Engine 24+ / Docker Desktop with Compose v2.
- A Google Gemini API key (`GEMINI_API_KEY`). Embeddings and answers are real —
  ingestion fails at EXTRACTING/EMBEDDING without a working key, so verified
  document-grounded answers depend on it.

## Environment variables

Copy `.env.example` to `.env`. Required, no sensible default:

| Variable | Notes |
| --- | --- |
| `GEMINI_API_KEY` | Backend only; never exposed to the client |
| `JWT_SECRET` | ≥ 32 bytes; `openssl rand -base64 48` |
| `DATABASE_PASSWORD` | Used for the `veridoc` Postgres role |

Everything else has the defaults in `.env.example`. Inside the compose network
the backend is given the compose-service URLs (`postgres:5432`, `redis:6379`)
and `STORAGE_ROOT=/app/data/storage` regardless of the host `.env` values —
the `.env` URLs are for non-Docker runs.

Notable knobs: `MAX_FILE_SIZE_BYTES` / `MAX_PDF_PAGES` (upload caps),
`RATE_LIMIT_*` (per-route buckets), `RAG_ENABLE_QUERY_REWRITING`,
`RAG_DEBUG_ENABLED` (admin debugger), `RETRIEVAL_*` (similarity search).

## Volumes

- `postgres-data` — the entire database + vectors. Survives rebuilds;
  destroy only with `docker compose down -v`.
- `storage-data` — uploaded PDFs, mounted at `/app/data`.
- Neither should be placed on networked filesystems that don't support Postgres
  locking semantics.

## Building

- **Backend**: the Dockerfile builds on the **host network**
  (`build.network: host`) to work around a Docker Desktop BuildKit DNS issue
  when resolving Maven Central during `dependency:go-offline`. BuildKit secrets
  and agents on this step are avoided on purpose.
- **Frontend**: `node:22-alpine` runs `npm ci && npm run build`; the runtime is
  `nginx:1.27-alpine` serving `/usr/share/nginx/html`.

## Runtime behavior

- **Frontend/nginx**: `/` falls back to `index.html` (SPA routes); `/api/` is
  proxied to `http://backend:8080` with `proxy_buffering off` so the SSE chat
  stream reaches the browser un-buffered. Same-origin proxying removes CORS
  from the production path.
- **Backend health**: the image healthcheck hits `/actuator/health/readiness`
  (a container only becomes `healthy`, and only starts satisfying
  `depends_on: service_healthy`, when Flyway migrations are done — so the
  frontend never boots against an un-migrated database).
- **Graceful degradation**: if Gemini is down, chat fails with stable error
  codes (`LLM_UNAVAILABLE`, `LLM_TIMEOUT`) rather than a partial answer.

## Operational notes

- **Errors are structured** — parse `code` from the JSON envelope, not the HTTP
  status alone.
- **Rate limits** live in Redis with `noeviction`; emptying means eviction is
  deliberate, and counters reset on cache loss (acceptable for quotas).
- **Logs**: `LOG_FORMAT=console|json`, `LOG_LEVEL` from `LOG_LEVEL`. The image
  ships no appenders beyond the defaults (`CONSOLE`/`JSON` are declared but not
  referenced) — the Spring `logging.*` config controls output.
- **Multi-user isolation is per-query**. There is no document-level share link,
  no team concept; the system is strictly per-owner.

## Day-2 checklist

1. Put a real `GEMINI_API_KEY` and a freshly generated `JWT_SECRET` in `.env`;
   never commit `.env`.
2. `docker compose up -d --build`, wait for 4 `healthy` services.
3. Upload a real PDF, wait for `READY`, attach it, and ask a question it can
   answer — verify the citation renders with a page number.
4. Ask an out-of-scope question and confirm the no-evidence refusal.
5. For the evaluation harness: set `RAG_DEBUG_ENABLED=true`, run
   `backend/src/test/.../RagEvaluationIT` against the live stack.
6. Back up `postgres-data`. Restore by mounting the backup into a fresh
   `postgres` volume before first boot.

## Local (non-Docker) development

- The backend can run on the host with a local Postgres/pgvector pointed at by
  `DATABASE_URL`/`REDIS_URL`. On Windows a pre-existing Postgres on `:5432`
  shadows the compose container: keep the backend in Docker, or stop the local
  service.
- The Vite dev server proxies `/api` → `http://localhost:8080` and needs
  `CORS_ALLOWED_ORIGINS=http://localhost:5173`; this is only a dev convenience,
  irrelevant to the nginx topology.
- Backend tests need a Docker daemon for Testcontainers.