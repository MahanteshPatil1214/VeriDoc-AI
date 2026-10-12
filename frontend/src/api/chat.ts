import type { ApiClient } from './client'
import { ApiError } from './types'
import type { ChatReply, Citation, Message, StreamEvent } from './types'

import { sseEvents } from '../lib/sse'

/** Message thread and chat endpoints. Streaming happens over SSE. */
export const chatApi = (client: ApiClient) => ({
  messages: (conversationId: string) =>
    client.json<Message[]>(`/api/v1/conversations/${conversationId}/messages`),

  ask: (conversationId: string, message: string) =>
    client.json<ChatReply>(`/api/v1/conversations/${conversationId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),

  /**
   * Opens the SSE stream for a chat turn and yields normalized events
   * ({@code token}, {@code citations}, {@code done}, {@code error}). The stream
   * endpoint is a GET with the bearer header, so it cannot use EventSource;
   * {@link sseEvents} parses the fetched body stream instead.
   */
  streamAssistant: async function* (
    conversationId: string,
    message: string,
    signal?: AbortSignal,
  ): AsyncGenerator<StreamEvent> {
    const query = new URLSearchParams({ message }).toString()
    const response = await client.stream(
      `/api/v1/conversations/${conversationId}/stream?${query}`,
      signal,
    )
    if (!response.ok) {
      throw await failureFrom(response)
    }
    if (!response.body) {
      throw new ApiError(response.status, 'NO_STREAM', 'Response has no body stream')
    }
    for await (const frame of sseEvents(response.body)) {
      switch (frame.event) {
        case 'token':
          yield { event: 'token', text: (JSON.parse(frame.data) as { text: string }).text }
          break
        case 'citations':
          yield {
            event: 'citations',
            citations: (JSON.parse(frame.data) as { citations: Citation[] }).citations,
          }
          break
        case 'done':
          yield { event: 'done', reply: JSON.parse(frame.data) as ChatReply }
          break
        case 'error': {
          const err = JSON.parse(frame.data) as { code: string; message: string }
          yield { event: 'error', code: err.code, message: err.message }
          break
        }
        default:
          break
      }
    }
  },
})

async function failureFrom(response: Response): Promise<ApiError> {
  const text = await response.text()
  try {
    const problem = JSON.parse(text) as { code: string; message: string }
    return new ApiError(response.status, problem.code, problem.message)
  } catch {
    return new ApiError(response.status, 'UNKNOWN_ERROR', `Request failed (${response.status})`)
  }
}