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

/** Wrap the bearer token from anywhere so the client stays context-free. */
export interface TokenProvider {
  getAccessToken: () => string | null
}

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