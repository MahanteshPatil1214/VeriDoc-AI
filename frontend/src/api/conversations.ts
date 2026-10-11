import type { ApiClient } from './client'
import type { Conversation, ConversationListItem } from './types'

/** Conversation lifecycle endpoints. */
export const conversationsApi = (client: ApiClient) => ({
  list: () => client.json<ConversationListItem[]>('/api/v1/conversations'),

  create: (title: string) =>
    client.json<Conversation>('/api/v1/conversations', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  detail: (id: string) => client.json<Conversation>(`/api/v1/conversations/${id}`),

  attach: (id: string, documentIds: string[]) =>
    client.json<Conversation>(`/api/v1/conversations/${id}/documents`, {
      method: 'POST',
      body: JSON.stringify({ documentIds }),
    }),
})