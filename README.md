# VeriDoc AI

**Evidence-Grounded Document Intelligence — Local Development**

Upload PDFs, ask questions in natural language, and get answers generated **only from your documents** — with inline citations you can verify. If the uploaded documents cannot support an answer, VeriDoc AI refuses instead of hallucinating.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)](https://adoptium.net/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![Vite](https://img.shields.io/badge/Vite-latest-646CFF?logo=vite&logoColor=white)](https://vitejs.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![pgvector](https://img.shields.io/badge/pgvector-latest-1A6BFF)](https://github.com/pgvector/pgvector)
[![Redis 7](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![Docker Compose](https://img.shields.io/badge/Docker%20Compose-latest-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![Groq](https://img.shields.io/badge/AI-Groq-000000)](https://groq.com/)
[![Google Gemini](https://img.shields.io/badge/AI-Google%20Gemini-4285F4)](https://ai.google.dev/)

---

## What is VeriDoc AI?

VeriDoc AI is a multi-user Retrieval-Augmented Generation (RAG) platform built for **local development**. It lets you upload PDF documents, attach them to conversations, and ask questions whose answers are grounded strictly in your uploaded content — complete with verifiable page-level citations.

The core product principle:

> **"If the answer cannot be supported by the uploaded documents, VeriDoc AI must clearly say that the information could not be found instead of hallucinating an answer."**

### Key features

- **Upload & ingest** one or more PDFs — extracted, chunked, and embedded asynchronously
- **Multi-turn chat** with streamed (SSE) answers and inline source citations (document, page, quoted text)
- **Strict per-owner isolation** — users can only access their own documents and conversations
- **Soft delete** drops vectors from retrieval while preserving the evidence trail of previous citations
- **Duplicate detection** (user-aware SHA-256) avoids re-processing identical uploads
- **Structured errors** with stable error codes — no internal stack traces exposed to users
- **Backend-only AI keys** — Groq (chat) and Gemini (embeddings) are never exposed to the browser

---

## Tech stack

| Layer | Technology |
|-------|------------|
| **Frontend** | React 19, Vite, TypeScript, TanStack Query, Zustand, hand-rolled Markdown |
| **Backend** | Java 21, Spring Boot 4.1, Spring AI, WebFlux (streaming) |
| **Database** | PostgreSQL 17 + pgvector |
| **Cache** | Redis 7 (rate-limit counters) |
| **AI** | Groq (chat), Google Gemini (embeddings) |
| **Infra** | Docker Compose, nginx (frontend) |

---

## Quick start — local development

### Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)
- A [Groq API key](https://console.groq.com/keys) for chat
- A [Google Gemini API key](https://aistudio.google.com) for embeddings

### 1. Clone and configure

```bash
git clone https://github.com/your-org/veridoc-ai.git
cd veridoc-ai
cp .env.example .env
```

Edit `.env` and set at least:

- `GROQ_API_KEY` — your Groq chat key
- `GEMINI_API_KEY` — your Gemini embeddings key
- `JWT_SECRET` — at least 32 bytes (`openssl rand -base64 48`)

> **Never commit `.env`.** It is gitignored and contains local secrets.

### 2. Start the stack

```bash
docker compose up -d --build
```

This starts PostgreSQL + pgvector, Redis, the backend (port 8080), and the nginx-served frontend (port 8081).

Open <http://localhost:8081>. nginx proxies `/api` to the backend on the same origin, so no CORS configuration is required.

### 3. Run services individually

**Backend only** (against compose services):

```bash
docker compose up -d --build backend
```

**Frontend only** (Vite dev server, proxies `/api` → `http://localhost:8080`):

```bash
cd frontend
npm install
npm run dev
```

---

## Configuration

Configuration is externalized via `.env`. See `.env.example` for the full list of variables with defaults.

| Variable | Purpose | Default |
|----------|---------|---------|
| `DATABASE_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5432/veridoc` |
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` |
| `GROQ_API_KEY` | Groq chat API key (backend only) | — required |
| `GEMINI_API_KEY` | Gemini embeddings API key (backend only) | — required |
| `JWT_SECRET` | JWT signing secret (≥ 32 bytes) | — required |
| `STORAGE_ROOT` | Directory for uploaded PDFs | `./data/storage` |
| `SERVER_PORT` | Backend port | `8080` |
| `FRONTEND_PORT` | Frontend (nginx) host port | `8081` |

Inside Docker Compose, the backend uses compose-service addresses (`postgres:5432`, `redis:6379`) and `STORAGE_ROOT=/app/data/storage` regardless of host `.env` values. The `.env` URLs apply to non-Docker runs.

---

## Project structure

```
veridoc-ai/
├── .env.example            # environment template (copy to .env)
├── .gitignore              # git ignore rules
├── docker-compose.yml      # local development stack
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
|----------|-------------|
| [Architecture](docs/ARCHITECTURE.md) | Components, ingestion pipeline, retrieval, security, data model |
| [API](docs/API.md) | Endpoints, payloads, SSE contract, error codes |
| [Deployment](docs/DEPLOYMENT.md) | Environment variables, volumes, operations |

---

## Security

- API keys (`GROQ_API_KEY`, `GEMINI_API_KEY`) and `JWT_SECRET` are backend-only and never bundled into frontend code
- `.env` is gitignored — never commit real credentials
- Vector retrieval always respects per-user authorization; a user can never retrieve another user's document chunks
- Retrieved document content is treated as evidence, never as executable instructions (prompt-injection resistant)
- Error responses never expose internal stack traces

---

## Roadmap

- [x] User registration, login, and JWT authentication
- [x] PDF upload, validation, and duplicate detection
- [x] Asynchronous ingestion: extraction → chunking → embedding → indexing
- [x] Vector search with per-user authorization in pgvector
- [x] Grounded RAG answers with page-level citations
- [x] Multi-turn, multi-document conversations with SSE streaming
- [x] Rate limiting and structured error handling
- [ ] OCR for scanned/image-only PDFs
- [ ] Hybrid search (BM25 + vector)
- [ ] Document sharing and workspaces

---

## Contributing

Contributions are welcome! Please open an issue to discuss proposed changes, then submit a pull request.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -m "feat: add your feature"`)
4. Push to the branch (`git push origin feature/your-feature`)
5. Open a pull request

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  <b>VeriDoc AI</b> · Evidence-Grounded Document Intelligence
</p>
