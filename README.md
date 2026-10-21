# VeriDoc AI

Evidence-grounded document intelligence. Upload PDFs, ask questions in natural
language, and get answers generated **only from your documents** — with inline
citations you can verify.

If the uploaded documents cannot support an answer, VeriDoc AI refuses instead
of hallucinating: it replies _"I couldn't find enough information in the
uploaded documents to answer that."_

## What it is

- Multi-user RAG platform backed by **PostgreSQL + pgvector**, **Groq** (chat)
  and **Google Gemini** (embeddings).
- Upload one or more PDFs; each is extracted, chunked and embedded asynchronously.
- Attach documents to a conversation and ask multi-turn questions with **streamed**
  answers and **source citations** (page, section, quoted text).
- Strict **per-owner isolation**: users can only see, attach, retry, rename or
  delete their own documents and conversations.
- Soft-deleted documents drop their vectors and disappear from retrieval; the
  evidence trail of previous citations survives deletion.
- Production-minded: JWT auth, rate limits, error taxonomy, Flyway migrations,
  Testcontainers integration tests, and a full containerized stack.

## Stack

| Layer     | Technology |
| --------- | ---------- |
| Frontend  | React 19, Vite, TypeScript, TanStack Query, Zustand, hand-rolled Markdown |
| Backend   | Java 21, Spring Boot 4.1, Spring AI, WebFlux (streaming) |
| Database  | PostgreSQL 17 + pgvector |
| Cache     | Redis 7 (rate-limit counters) |
| AI        | Groq (chat), Google Gemini (embeddings) |
| Infra     | Docker Compose, nginx (frontend), multi-stage images |

## Quick start (Docker Compose)

Prerequisites: Docker Desktop (or equivalent), a [Groq API key](https://console.groq.com/keys)
for chat and a [Google Gemini API key](https://aistudio.google.com) for embeddings.

```bash
cp .env.example .env        # then fill in GROQ_API_KEY, GEMINI_API_KEY and JWT_SECRET
docker compose up -d --build
```

Open <http://localhost:8081>. The stack starts Postgres + pgvector, Redis, the
backend (port 8080) and the nginx-served frontend (port 8081). nginx proxies
`/api` to the backend on the same origin, so no CORS configuration is involved.

> Both AI keys are backend-only: Groq serves answer generation, Gemini serves
> vector embeddings, and neither is ever exposed to the browser.

## Local development

Backend (runs in the compose network against `postgres`/`redis` service names):

```bash
docker compose up -d --build backend
```

Frontend (Vite dev server, uses host network, proxies `/api` → `http://localhost:8080`):

```bash
cd frontend
npm install
npm run dev
```

## Testing

```bash
cd backend
mvn verify                       # unit + integration tests (Testcontainers)
cd frontend && npm run lint && npm run build
```

Integration tests use a disposable pgvector container and deterministic
Gemini/Groq stubs — no external API calls, no real PDFs required.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — components, ingestion pipeline, retrieval, security, data model.
- [API](docs/API.md) — endpoints, payloads, SSE contract, error codes.
- [Deployment](docs/DEPLOYMENT.md) — environment variables, volumes, operations.