# VeriDoc AI — API

Base URL: `/api/v1`. All JSON bodies use UTF-8. All endpoints except
`POST /auth/register`, `POST /auth/login` and `POST /auth/refresh` require an
`Authorization: Bearer <accessToken>` header.

## Error envelope

Every non-2xx response is JSON:

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "Document not found.",
  "details": {},
  "timestamp": "2026-10-12T16:40:58.857006498Z",
  "traceId": "unknown"
}
```

`code` is a stable machine-readable string; `message` is human-readable.
Unknown/invalid authentication yields `401 UNAUTHORIZED`; foreign (other
user's) resources are hidden as `404`, never `403`.

### Error codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | Malformed request |
| `VALIDATION_FAILED` | 400 | Bean-validation failure |
| `UNAUTHORIZED` | 401 | Missing/invalid credentials |
| `FORBIDDEN` | 403 | Not allowed (e.g. admin-only) |
| `NOT_FOUND` | 404 | Generic missing resource |
| `DOCUMENT_NOT_FOUND` | 404 | Document missing / not yours |
| `CONVERSATION_NOT_FOUND` | 404 | Conversation missing / not yours |
| `MESSAGE_NOT_FOUND` | 404 | Message missing |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP verb |
| `UNSUPPORTED_FILE` | 415 | Not a supported upload type |
| `DOCUMENT_TOO_LARGE` | 413 | Exceeds size cap |
| `INVALID_PDF` | 422 | Not a readable PDF |
| `EMPTY_DOCUMENT` | 422 | PDF has no extractable text |
| `ENCRYPTED_PDF` | 422 | PDF is encrypted |
| `DUPLICATE_DOCUMENT` | 409 | Same bytes already uploaded |
| `DOCUMENT_NOT_READY` | 409 | Attach/query on a non-READY document |
| `CONFLICT` | 409 | General conflict (e.g. retry a non-failed doc) |
| `UNAUTHORIZED_DOCUMENT` | 403 | Result references a document user lost access to |
| `RATE_LIMIT_EXCEEDED` | 429 | Bucket exhausted |
| `LLM_RATE_LIMITED` | 429 | LLM provider (Groq) throttled |
| `DOCUMENT_PROCESSING_FAILED` | 500 | Ingestion pipeline failed |
| `EMBEDDING_FAILED` | 502 | Embedding model error |
| `RETRIEVAL_FAILED` | 502 | Vector search failed |
| `LLM_UNAVAILABLE` | 502 | LLM provider (Groq) unreachable |
| `LLM_TIMEOUT` | 504 | LLM HTTP request timed out (provider unresponsive) |
| `STREAM_INTERRUPTED` | 500 | Stream aborted mid-flight |
| `INTERNAL_ERROR` | 500 | Catch-all |

## Authentication

### `POST /auth/register` — create account → 201

```json
{ "email": "ada@example.com", "password": "at-least-12-chars", "displayName": "Ada" }
```

`password` 12–128 chars. Returns `AuthResponse`:

```json
{
  "tokenType": "Bearer",
  "accessToken": "<jwt>",
  "refreshToken": "<jwt>",
  "expiresInSeconds": 3600,
  "user": { "id": "…", "email": "…", "displayName": "Ada", "role": "USER", "createdAt": "…" }
}
```

### `POST /auth/login` — exchange credentials → 200

Same request/response shape as register.

### `POST /auth/refresh` — rotate refresh token → 200

```json
{ "refreshToken": "<jwt>" }
```

Returns a fresh `AuthResponse`.

### `PUT /auth/password` — change the current password → 204

Requires a bearer token.

```json
{ "currentPassword": "current-secret", "newPassword": "at-least-12-chars" }
```

The current password must match or the request fails with
`400 INVALID_REQUEST`. `newPassword` follows the same 12–128 character rule as
registration. A successful change increments the account's token version, so
every previously issued refresh token is rejected. Access tokens are not
revoked immediately; they remain valid until their normal TTL expires (one hour
by default). The client should discard its tokens and sign in again.

> Self-service password reset is intentionally unavailable until a verified
> ownership flow (for example, an emailed single-use token) exists. There is no
> `POST /auth/forgot-password` endpoint.

### `GET /auth/me` — current user → 200

Returns `{ id, email, displayName, role, createdAt }`.

## Documents

### `POST /documents` — upload a PDF → 201

Multipart form, file field `file` (`application/pdf`, ≤ 20 MiB, ≤ 500 pages).

```json
{ "id": "…", "filename": "policy.pdf", "status": "UPLOADING", "sizeBytes": 482351 }
```

Processing continues asynchronously; poll `GET /documents/{id}`.

### `GET /documents` — list my documents → 200

`DELETED` documents are excluded.

```json
[{ "id": "…", "filename": "policy.pdf", "status": "READY", "pageCount": 12, "chunkCount": 41, "createdAt": "…" }]
```

### `GET /documents/{id}` — status → 200

```json
{ "id": "…", "status": "FAILED", "stage": "EXTRACTING", "error": "No extractable text found in the PDF", "pageCount": null, "chunkCount": null }
```

`status` lifecycle: `UPLOADING → PROCESSING → EXTRACTING → CHUNKING → EMBEDDING
→ INDEXING → READY`, with any step able to end in `FAILED`. Deleted documents
return `status: "DELETED"`.

### `DELETE /documents/{id}` — delete → 204

Soft delete: idempotent, drops derived chunks/versions and the stored file,
hides the document, and frees a same-bytes re-upload. Other users' documents →
`404 DOCUMENT_NOT_FOUND`.

### `POST /documents/{id}/retry` — retry failed ingestion → 200

Only `FAILED` documents may be retried; otherwise `409 CONFLICT`. Returns the
same shape as `GET /documents/{id}` (immediately `PROCESSING`).

## Conversations

### `POST /conversations` — create → 201

```json
{ "title": "Q3 review" }        // title optional, ≤ 255 chars
```

Returns `ConversationResponse`:

```json
{
  "id": "…", "title": "Q3 review",
  "createdAt": "…", "updatedAt": "…",
  "documents": [{ "id": "…", "filename": "policy.pdf" }]
}
```

### `GET /conversations` — list mine → 200

```json
[{ "id": "…", "title": "Q3 review", "updatedAt": "…" }]
```

Ordered most-recently-updated first.

### `GET /conversations/{id}` — detail → 200

Full `ConversationResponse`.

### `PATCH /conversations/{id}` — rename → 200

```json
{ "title": "Quarterly financials" }    // required, ≤ 255 chars
```

Returns the updated `ConversationResponse`.

### `DELETE /conversations/{id}` — delete → 204

Cascades to messages, citations and document links. Other users' conversations
→ `404 CONVERSATION_NOT_FOUND`.

### `POST /conversations/{id}/documents` — attach documents → 200

```json
{ "documentIds": ["…", "…"] }
```

All listed documents must exist, belong to the caller and be `READY`
(otherwise `404 DOCUMENT_NOT_FOUND` / `409 DOCUMENT_NOT_READY`). Returns the
updated `ConversationResponse`.

## Messages

### `POST /conversations/{id}/messages` — ask (non-streaming) → 200

```json
{ "message": "What does the policy cover?" }
```

`message` is required, ≤ 8000 chars. Returns `ChatResponseDto`:

```json
{
  "messageId": "…", "conversationId": "…", "role": "assistant",
  "content": "The policy covers…",
  "status": "COMPLETED", "model": "openai/gpt-oss-120b",
  "promptTokens": 120, "completionTokens": 40, "totalTokens": 160, "latencyMs": 840,
  "createdAt": "…",
  "citations": [{
    "ordinal": 1, "documentId": "…", "documentFilename": "policy.pdf",
    "chunkId": "…", "pageNumber": 3, "section": "Coverage",
    "relevanceScore": 0.91, "quotedText": "…"
  }]
}
```

When no supporting evidence is found, `content` is the no-evidence refusal:
"I couldn't find enough information in the uploaded documents to answer that."

### `GET /conversations/{id}/messages` — history → 200

```json
[{
  "id": "…", "role": "USER", "content": "…", "status": "COMPLETE", "createdAt": "…",
  "feedback": "UP", "feedbackComment": "Clear and cited."
}]
```

Each assistant message carries its persisted `citations` (same shape as the ask
response), so sources survive a page reload. `feedback`/`feedbackComment` are
the caller's own rating of an assistant answer, or `null`.

Assistant `content` may contain inline citation markers (`[1]`, `【1】`, or
`【1†L5-L7】`) that reference source 1. The UI renders these as clickable
superscripts linked to the message's `citations`; they are not a separate
references list.

### `PUT /conversations/{id}/messages/{messageId}/feedback` — rate an answer → 200

```json
{ "rating": "DOWN", "comment": "Missed the deadline on page 2." }
```

`rating` is `UP` or `DOWN`; `comment` is optional and ≤ 2000 chars. Submitting
again replaces the existing rating (one per user/message). Only completed
assistant answers can be rated (`400 INVALID_REQUEST`); foreign messages and
conversations are hidden as `404`.

Returns `{ id, messageId, rating, comment }`.

### `DELETE /conversations/{id}/messages/{messageId}/feedback` — clear rating → 204

Removes the caller's rating. Deleting a rating that does not exist is still
`204`.

### `GET /conversations/{id}/stream?message=…` — ask (SSE) → `text/event-stream`

Requires the bearer header (not `EventSource`). Stream contract:

```
event: token
data: {"text":"The policy covers…"}         // zero or more, incremental

event: citations
data: {"citations":[{ … ordinal, documentId, documentFilename, chunkId, pageNumber, section, relevanceScore, quotedText }]}

event: done
data: {"reply":{ "messageId":"…", "message":"…", "status":"COMPLETED", "createdAt":"…", … }}
```

Failures emit a terminal `error` event (not an HTTP error mid-stream):

```
event: error
data: {"code":"LLM_TIMEOUT","message":"The answer could not be generated. Please try again."}
```

Ownership/validation problems are still answered with a normal JSON `4xx`
before any stream bytes are written.

## Admin

### `GET /admin/rag/debug?conversationId=…&question=…` — RAG debugger → 200

Requires `ROLE_ADMIN` **and** `RAG_DEBUG_ENABLED=true` (otherwise disabled).
Exposes the intermediate stages (rewrite, retrieval, prompt) for a question.
Optional; used by the evaluation harness.