import type { ApiClient } from './client'
import type { DocumentListItem, DocumentStatusResponse, UploadResponse } from './types'

/** Document library: upload, list and per-document ingestion status. */
export const documentsApi = (client: ApiClient) => ({
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return client.upload<UploadResponse>('/api/v1/documents', form)
  },

  list: () => client.json<DocumentListItem[]>('/api/v1/documents'),

  status: (id: string) => client.json<DocumentStatusResponse>(`/api/v1/documents/${id}`),
})
