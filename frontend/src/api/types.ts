/** Mirrors the backend JSON contracts we consume. */

export interface User {
  id: string
  email: string
  displayName: string
  role: string
  createdAt: string
}

export interface AuthResponse {
  tokenType: string
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: User
}

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}

export interface LoginRequest {
  email: string
  password: string
}

/** Self-service reset: the account email plus the replacement password. */
export interface ForgotPasswordRequest {
  email: string
  newPassword: string
}

/** Wrap the bearer token from anywhere so the client stays context-free. */
export interface TokenProvider {
  getAccessToken: () => string | null
}

// ---- conversations ----

export interface ConversationDocumentRef {
  id: string
  filename: string
}

export interface Conversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  documents: ConversationDocumentRef[]
}

export interface ConversationListItem {
  id: string
  title: string
  updatedAt: string
}

// ---- documents ----

/** Mirrors the backend DocumentStatus enum; unknown values fall back to string. */
export type DocumentStatus =
  | 'UPLOADING'
  | 'PROCESSING'
  | 'EXTRACTING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'READY'
  | 'FAILED'
  | 'DELETED'

export interface DocumentListItem {
  id: string
  filename: string
  status: DocumentStatus
  pageCount: number | null
  chunkCount: number | null
  createdAt: string
}

export interface DocumentStatusResponse {
  id: string
  status: DocumentStatus
  stage: string | null
  error: string | null
  pageCount: number | null
  chunkCount: number | null
}

export interface UploadResponse {
  id: string
  filename: string
  status: DocumentStatus
  sizeBytes: number
}

// ---- messages / chat ----

export interface Message {
  id: string
  role: string
  content: string
  status: string
  createdAt: string
  /** Citations are attached while streaming; the history feed does not include them. */
  citations?: Citation[]
}

export interface Citation {
  ordinal: number
  documentId: string
  documentFilename: string
  chunkId: string
  pageNumber: number | null
  section: string | null
  relevanceScore: number | null
  quotedText: string
}

export interface ChatReply {
  messageId: string
  conversationId: string
  role: string
  content: string
  status: string
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  createdAt: string
  citations: Citation[]
}

// ---- streaming (SSE over GET) ----

/** The normalized events the assistant stream can yield. */
export type StreamEvent =
  | { event: 'token'; text: string }
  | { event: 'citations'; citations: Citation[] }
  | { event: 'done'; reply: ChatReply }
  | { event: 'error'; code: string; message: string }

/** Backend error envelope: { code, message } with a stable HTTP status. */
export interface ApiProblem {
  code: string
  message: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}