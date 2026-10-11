import { ApiError } from './types'
import type { ApiProblem, TokenProvider } from './types'

/** Transport handle: escape hatch for tests and non-browser environments. */
export interface HttpApi {
  fetch: typeof fetch
}

/**
 * Typed fetch wrapper. Attaches the bearer token, parses the backend's
 * {@code {code, message}} error envelope into {@link ApiError}, and invokes an
 * {@link onUnauthorized} hook on 401 so the app can re-authenticate silently.
 */
export class ApiClient {
  readonly getAccessToken?: () => string | null
  /** Invoked on any 401 so the app can refresh the session silently. */
  onUnauthorized?: () => void
  readonly http?: HttpApi

  constructor(options?: {
    tokenProvider?: TokenProvider
    onUnauthorized?: () => void
    http?: HttpApi
  }) {
    this.getAccessToken = options?.tokenProvider?.getAccessToken
    this.onUnauthorized = options?.onUnauthorized
    this.http = options?.http
  }

  /** JSON request. Returns parsed body; throws {@link ApiError} on non-2xx. */
  async json<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    const token = this.getAccessToken?.()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    const perform = this.http?.fetch ?? fetch
    const response = await perform(path, { ...init, headers })
    const text = await response.text()
    if (!response.ok) {
      await this.handleFailure(response, text)
    }
    return (text.length === 0 ? null : JSON.parse(text)) as T
  }

  /** Multipart upload; the browser sets the multipart boundary automatically. */
  async upload<T>(path: string, formData: FormData): Promise<T> {
    const token = this.getAccessToken?.()
    const headers = new Headers()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    const perform = this.http?.fetch ?? fetch
    const response = await perform(path, { method: 'POST', headers, body: formData })
    const text = await response.text()
    if (!response.ok) {
      await this.handleFailure(response, text, false)
    }
    return JSON.parse(text) as T
  }

  /** Opens a stream (SSE / any body stream) with the bearer header. */
  async stream(path: string, signal?: AbortSignal): Promise<Response> {
    const token = this.getAccessToken?.()
    const headers = new Headers()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    const perform = this.http?.fetch ?? fetch
    return perform(path, { headers, signal, cache: 'no-store' })
  }

  private async handleFailure(response: Response, text: string, parse = true): Promise<never> {
    let problem: ApiProblem | undefined
    if (parse) {
      try {
        problem = JSON.parse(text) as ApiProblem
      } catch {
        problem = undefined
      }
    }
    if (response.status === 401) {
      this.onUnauthorized?.()
    }
    throw new ApiError(
      response.status,
      problem?.code ?? 'UNKNOWN_ERROR',
      problem?.message ?? `Request failed (${response.status})`,
    )
  }
}