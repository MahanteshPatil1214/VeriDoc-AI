import type { ApiClient } from './client'
import type { ChatReply, Message } from './types'

/** Message thread and chat endpoints. Streaming variants come later. */
export const chatApi = (client: ApiClient) => ({
  messages: (conversationId: string) =>
    client.json<Message[]>(`/api/v1/conversations/${conversationId}/messages`),

  ask: (conversationId: string, message: string) =>
    client.json<ChatReply>(`/api/v1/conversations/${conversationId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),
})