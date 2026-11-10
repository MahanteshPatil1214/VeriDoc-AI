# VeriDoc AI

**Evidence-Grounded Document Intelligence — Local Development**

Upload PDFs, ask questions in natural language, and get answers generated **only from your documents** — with inline citations you can verify.

If the uploaded documents cannot support an answer, VeriDoc AI refuses instead of hallucinating: it replies _"I couldn't find enough information in the uploaded documents to answer that."_

---

## What it is

VeriDoc AI is a multi-user Retrieval-Augmented Generation (RAG) platform for local development. It lets you:

- Upload one or more PDFs; each is extracted, chunked, and embedded asynchronously.
- Attach documents to a conversation and ask multi-turn questions with **streamed** answers and **source citations** (page, section, quoted text).
- Enforce **strict per-owner isolation**: users can only see, attach, retry, rename, or delete their own documents and conversations.
- Drop vectors for soft-deleted documents while preserving the evidence trail of previous citations.

---

## Stack

| Layer     | Technology |
| --------- | ---------- |
| Frontend  | React 19, Vite, TypeScript, TanStack Query, Zustand, hand-rolled Markdown |
| Backend   | Java 21, Spring Boot 4.1, Spring AI, WebFlux (streaming) |
| Database  | PostgreSQL 17 + pgvector |
| Cache     | Redis 7 (rate-limit counters) |
| AI        | Groq (chat), Google Gemini (embeddings) |
| Infra     | Docker Compose, nginx (frontend) |

> Both AI keys are backend-only: Groq serves answer generation, Gemini serves vector embeddings, and neither is ever exposed to the browser.

---

## Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)
- A [Groq API key](https://console.groq.com/keys) for chat
- A [Google Gemini API key](https://aistudio.google.com) for embeddings
- (Optional) A local PostgreSQL + pgvector instance if running the backend outside Docker

---

## Quick start — local development

### 1. Configure the environment

Copy the example environment file and fill in your API keys and secret:

```bash
cp .env.example .env
```

Edit `.env` and set at least:

- `GROQ_API_KEY` — your Groq chat key
- `GEMINI_API_KEY` — your Gemini embeddings key
- `JWT_SECRET` — at least 32 bytes (generate with `openssl rand -base64 48`)

> **Never commit `.env`.** It is gitignored and contains local secrets.

### 2. Start the stack

```bash
docker compose up -d --build
```

This starts PostgreSQL + pgvector, Redis, the backend (port 8080), and the nginx-served frontend (port 8081).

Open <http://localhost:8081>. nginx proxies `/api` to the backend on the same origin, so no CORS configuration is involved.

### 3. Backend only (against compose services)

```bash
docker compose up -d --build backend
```

### 4. Frontend only (Vite dev server)

The Vite dev server proxies `/api` → `http://localhost:8080`:

```bash
cd frontend
npm install
npm run dev
```

---

## Configuration

Configuration is externalized via `.env`. See `.env.example` for the full list of supported variables with defaults.

Key local-development variables:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `DATABASE_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5432/veridoc` |
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` |
| `GROQ_API_KEY` | Groq chat API key (backend only) | — required |
| `GEMINI_API_KEY` | Gemini embeddings API key (backend only) | — required |
| `JWT_SECRET` | JWT signing secret (≥ 32 bytes) | — required |
| `STORAGE_ROOT` | Directory for uploaded PDFs | `./data/storage` |
| `SERVER_PORT` | Backend port | `8080` |
| `FRONTEND_PORT` | Frontend (nginx) host port | `8081` |

Inside Docker Compose, the backend is given compose-service addresses (`postgres:5432`, `redis:6379`) and `STORAGE_ROOT=/app/data/storage` regardless of the host `.env` values. The `.env` URLs apply to non-Docker runs.

---

## Project structure

```
veridoc-ai/
├── .env.example            # environment template (copy to .env)
├── .gitignore              # git ignore rules
├── docker-compose.yml      # local development stack (Postgres, Redis, backend, frontend)
├── README.md               # this file
├── PROJECT.txt             # product requirements & design spec
├── backend/                # Java 21 / Spring Boot application
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/...    # application source
│       │   └── resources/
│       │       ├── application.yml   # Spring Boot configuration
│       │       └── db/migration/     # Flyway migrations
│       └── test/...        # unit + integration tests
├── frontend/               # React 19 / Vite / TypeScript application
│   ├── package.json
│   ├── nginx.conf
│   └── src/
└── docs/
    ├── ARCHITECTURE.md     # components, ingestion, retrieval, security, data model
    ├── API.md              # endpoints, payloads, SSE contract, error codes
    └── DEPLOYMENT.md       # environment variables, volumes, operations
```

---

## Testing

```bash
# Backend: unit + integration tests (Testcontainers, disposable pgvector)
cd backend
mvn verify

# Frontend: lint and production build
cd ../frontend
npm run lint
npm run build
```

Integration tests use a disposable pgvector container and deterministic Gemini/Groq stubs — no external API calls and no real PDFs required.

---

## Documentation

| Document | Description |
| -------- | ----------- |
| [Architecture](docs/ARCHITECTURE.md) | Components, ingestion pipeline, retrieval, security, data model |
| [API](docs/API.md) | Endpoints, payloads, SSE contract, error codes |
| [Deployment](docs/DEPLOYMENT.md) | Environment variables, volumes, operations |

---

## Security notes

- API keys (`GROQ_API_KEY`, `GEMINI_API_KEY`) and `JWT_SECRET` are backend-only and never bundled into frontend code.
- `.env` is gitignored — never commit real credentials.
- Vector retrieval always respects per-user authorization; a user can never retrieve another user's document chunks.
- Retrieved document content is treated as evidence, never as executable instructions (prompt-injection resistant).
- Error responses never expose internal stack traces.

---

## License

Private project — all rights reserved.

---

<p align="center"><b>VeriDoc AI</b> · Evidence-Grounded Document Intelligence</p>
