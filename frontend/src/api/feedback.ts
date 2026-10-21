import type { ApiClient } from './client'
import type { FeedbackRating, FeedbackResponse } from './types'

/** Thumbs up/down on an assistant answer. Scoped under its conversation. */
export const feedbackApi = (client: ApiClient) => ({
  submit: (
    conversationId: string,
    messageId: string,
    request: { rating: FeedbackRating; comment?: string },
  ) =>
    client.json<FeedbackResponse>(
      `/api/v1/conversations/${conversationId}/messages/${messageId}/feedback`,
      { method: 'PUT', body: JSON.stringify(request) },
    ),

  clear: (conversationId: string, messageId: string) =>
    client.json<void>(
      `/api/v1/conversations/${conversationId}/messages/${messageId}/feedback`,
      { method: 'DELETE' },
    ),
})
