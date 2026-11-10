<div align="center">

# VeriDoc AI

### Evidence-Grounded Document Intelligence & Retrieval-Augmented Generation

<p>
An enterprise-grade, privacy-first Retrieval-Augmented Generation (RAG) system engineered for <b>local development</b>. Upload PDFs, interact through real-time streaming conversations, and receive verifiable answers grounded strictly in your source documents — with inline citations down to the exact page and passage. If source documents do not contain the answer, VeriDoc AI explicitly refuses rather than hallucinating.
</p>

[![CI](https://github.com/MahanteshPatil1214/VeriDoc-AI/actions/workflows/ci.yml/badge.svg)](https://github.com/MahanteshPatil1214/VeriDoc-AI/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)](https://adoptium.net/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![pgvector](https://img.shields.io/badge/pgvector-0.8+-1A6BFF)](https://github.com/pgvector/pgvector)
[![Redis 7](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![Docker Compose](https://img.shields.io/badge/Docker%20Compose-v2-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)

</div>

---

## Overview

VeriDoc AI addresses the critical failure mode of enterprise LLMs: **ungrounded hallucinations**. Rather than treating language models as knowledge bases, VeriDoc AI treats them strictly as reasoning and synthesis engines over verified document evidence.

### Core Tenet
> **"If an answer cannot be proven by the uploaded documents attached to the conversation, VeriDoc AI explicitly refuses to answer instead of guessing."**

---

## Key Highlights

- **Hybrid Retrieval Architecture**: Combines PostgreSQL `pgvector` HNSW cosine similarity search with PostgreSQL full-text search (`tsvector` with BM25-equivalent `ts_rank_cd`), merging and re-ranking candidates for both semantic and lexical relevance.
- **Verifiable Citations**: Answers contain inline citation pills referencing the specific document, version, and page number, complete with the excerpted evidence.
- **Strict Multi-Tenant Isolation**: Row-level security semantics ensure queries and document chunks are partitioned by authenticated owner identity directly in SQL queries.
- **Streaming Multi-Turn Dialogue**: Low-latency Server-Sent Events (SSE) streaming answers directly into a responsive chat workspace.
- **Robust Ingestion Pipeline**: Asynchronous PDF extraction via Apache Tika, token-aware chunking with sliding windows, duplicate detection via content SHA-256, and batch vector generation.
- **Security-First Boundary**: AI credentials (Groq and Google Gemini) remain securely on the backend; the browser interface never receives API tokens.

---

## Architectural Flow

```
+-------------------------------------------------------------------------------+
|                             INGESTION WORKFLOW                                |
|                                                                               |
|  [PDF Upload] -> [SHA-256 Check] -> [Apache Tika Parse] -> [Token Chunker]    |
|                                                                   |           |
|  [HNSW Vector Index] <- [pgvector] <- [Gemini Embeddings] <-------+           |
|          +                                                                    |
|  [GIN Keyword Index] <- [PostgreSQL tsvector Trigger]                         |
+-------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------+
|                              QUERY & RAG WORKFLOW                             |
|                                                                               |
|  [User Question] -> [Standalone Query Rewrite]                                 |
|                            |                                                  |
|              +-------------+-------------+                                    |
|              |                           |                                    |
|    [Vector Search (Cosine)]    [Full-Text Search (BM25)]                      |
|              |                           |                                    |
|              +-------------+-------------+                                    |
|                            |                                                  |
|               [Hybrid Candidate Merger & Re-ranker]                           |
|                            |                                                  |
|                 [Relevance Threshold Filter]                                  |
|                            |                                                  |
|          +-----------------+-----------------+                                |
|          | (Evidence found)                  | (Insufficient evidence)        |
|          v                                   v                                |
|  [Grounded Prompt Assembly]         [Canonical Refusal]                       |
|          |                                                                    |
|  [Groq LLM Streaming] -> [SSE Stream + Inline Page Citations]                |
+-------------------------------------------------------------------------------+
```

---

## Technology Stack

| Domain | Technology | Description |
| :--- | :--- | :--- |
| **Frontend** | React 19, TypeScript, Vite, TailwindCSS | High-performance SPA with client-side state via TanStack Query & Zustand |
| **Backend** | Java 21, Spring Boot 3.4, Spring AI | Enterprise service layer with reactive streaming endpoints |
| **Data & Vectors** | PostgreSQL 17 + `pgvector` | Relational metadata storage alongside HNSW vector indexing |
| **Text Search** | PostgreSQL `tsvector` + GIN Index | Lexical search using BM25 cover-density ranking |
| **Caching** | Redis 7 Alpine | Fast rate-limiting and session verification |
| **Embeddings** | Google Gemini (`gemini-embedding-001`) | High-dimensional semantic embeddings |
| **Language Model** | Groq (`openai/gpt-oss-120b` or LLaMA models) | Ultra-fast token generation for streaming responses |
| **DevOps** | Docker Compose | Complete containerized local development environment |

---

## Getting Started (Local Development)

### Prerequisites

1. **Docker Desktop** (or Docker Engine 24+ and Docker Compose v2)
2. **Groq API Key**: [console.groq.com/keys](https://console.groq.com/keys)
3. **Google Gemini API Key**: [aistudio.google.com](https://aistudio.google.com)

---

### Step 1: Clone the Repository

```bash
git clone https://github.com/MahanteshPatil1214/VeriDoc-AI.git
cd VeriDoc-AI
```

---

### Step 2: Configure Environment Variables

Create your local `.env` configuration file from the template:

```bash
cp .env.example .env
```

Open `.env` in your editor and configure your credentials:

```ini
# AI Provider Keys
GROQ_API_KEY=gsk_your_groq_api_key_here
GEMINI_API_KEY=your_gemini_api_key_here

# Security Key (Generate a random 32+ byte string)
JWT_SECRET=super-secret-jwt-signing-key-minimum-32-characters-long

# Local Ports (Optional defaults)
SERVER_PORT=8080
FRONTEND_PORT=8081
```

> **Security Note:** `.env` is ignored by Git and should never be checked into version control.

---

### Step 3: Run the Full Stack with Docker Compose

Launch the complete application environment (PostgreSQL with pgvector, Redis, Spring Boot backend, and React frontend):

```bash
docker compose up -d --build
```

Monitor container health and startup logs:

```bash
docker compose logs -f
```

Once running, access the user interface in your browser:

- **Web Application Workspace**: [http://localhost:8081](http://localhost:8081)
- **Backend Health Endpoint**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

To tear down the containers:

```bash
docker compose down
```

---

## Running Services Standalone (Without Full Docker)

For faster iterative development, run the dependencies in Docker and the apps directly on your host machine:

### 1. Start Infrastructure Only

```bash
docker compose up -d postgres redis
```

### 2. Run Backend (Java 21 + Maven)

```bash
cd backend
mvn spring-boot:run
```

The backend starts at `http://localhost:8080`.

### 3. Run Frontend (Node.js 20+ / npm)

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server starts at `http://localhost:5173` with automatic API proxying to port 8080.

---

## Hybrid Search Tuning

VeriDoc AI supports fine-grained tuning of hybrid retrieval weights in `.env`:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `RETRIEVAL_KEYWORD_ENABLED` | Toggle hybrid BM25 search alongside vector cosine search | `true` |
| `RETRIEVAL_VECTOR_WEIGHT` | Relative weight for semantic vector similarity | `0.7` |
| `RETRIEVAL_KEYWORD_WEIGHT` | Relative weight for lexical BM25 rank | `0.3` |
| `RETRIEVAL_TOP_K` | Number of final chunks included in evidence context | `8` |
| `RETRIEVAL_MIN_SCORE` | Minimum similarity score required to retain a chunk | `0.15` |

When `RETRIEVAL_KEYWORD_ENABLED=false`, the system seamlessly switches to pure pgvector cosine retrieval.

---

## Quality & Automated Verification

The test harness validates functionality without requiring external provider network calls:

```bash
# Backend unit tests and Testcontainers integration tests
cd backend
mvn clean verify

# Frontend linting and TypeScript compilation
cd ../frontend
npm run lint
npm run build
```

- **Backend Tests**: 130+ unit tests, plus end-to-end RAG dataset evaluation gates run against deterministic stubs.
- **Continuous Integration**: GitHub Actions automated pipeline validates all pull requests and pushes to `main`.

---

## Project Structure

```text
VeriDoc-AI/
├── .github/workflows/        # GitHub Actions continuous integration pipeline
├── backend/                  # Spring Boot 3.4 Java 21 service
│   ├── src/main/java/        # Application modules (auth, rag, document, security)
│   ├── src/main/resources/   # Configuration & Flyway database migrations
│   └── src/test/             # Unit and Testcontainers integration suites
├── frontend/                 # React 19 + TypeScript frontend application
│   ├── src/components/       # UI components (chat, upload, viewer, citations)
│   ├── src/hooks/            # Query & state management hooks
│   └── nginx.conf            # Reverse proxy & static serving config
├── docs/                     # Detailed architectural and API documentation
│   ├── ARCHITECTURE.md       # In-depth system design & data model
│   ├── API.md                # REST & SSE contracts
│   └── DEPLOYMENT.md         # Operational guidelines
├── docker-compose.yml        # Development environment orchestration
└── .env.example              # Template for environment configuration
```

---

## Roadmap

- [x] JWT-based user authentication & owner isolation
- [x] PDF asynchronous text extraction, token chunking, and batch embedding
- [x] Vector nearest-neighbor indexing with PostgreSQL `pgvector`
- [x] Hybrid search combining pgvector cosine similarity with `tsvector` BM25
- [x] Low-latency Server-Sent Events (SSE) streaming answers
- [x] Deterministic citation resolution with page-level verification
- [x] Continuous evaluation test gate preventing hallucination regressions
- [ ] OCR support for image-heavy and scanned documents
- [ ] Multi-document cross-comparison and synthesis views
- [ ] Role-based organization workspaces and document sharing

---

## Contributing

1. Fork the repository.
2. Create your feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m "feat: add amazing feature"`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a Pull Request.

---

## Acknowledgments

Built with open-source technologies: Spring AI, pgvector, React, Vite, and Apache Tika.
