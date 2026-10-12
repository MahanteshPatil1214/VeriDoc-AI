import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'

import { api, ApiError, conversationsApi, documentsApi } from '../api'
import type { DocumentListItem, DocumentStatus } from '../api'
import { useWorkspace } from '../chat/useWorkspace'
import {
  isFailed,
  isProcessing,
  isReady,
  statusLabel,
  statusStep,
} from '../lib/documentStatus'
import './DocumentsDrawer.css'

const MAX_BYTES = 20 * 1024 * 1024

export function DocumentsDrawer() {
  const { activeConversationId, documentsOpen, setDocumentsOpen } = useWorkspace()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const [localError, setLocalError] = useState<string | null>(null)
  const [attachError, setAttachError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [dragging, setDragging] = useState(false)

  const { data: documents = [] } = useQuery({
    queryKey: ['documents'],
    queryFn: () => documentsApi(api).list(),
    enabled: documentsOpen,
    refetchInterval: (query) =>
      (query.state.data ?? []).some((doc) => isProcessing(doc.status)) ? 2000 : false,
  })

  const { data: conversation } = useQuery({
    queryKey: ['conversation', activeConversationId],
    queryFn: () => conversationsApi(api).detail(activeConversationId!),
    enabled: documentsOpen && !!activeConversationId,
  })

  const uploadMutation = useMutation({
    mutationFn: (file: File) => documentsApi(api).upload(file),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
    },
  })

  const attachMutation = useMutation({
    mutationFn: (ids: string[]) => conversationsApi(api).attach(activeConversationId!, ids),
    onSuccess: () => {
      setSelected([])
      setAttachError(null)
      void queryClient.invalidateQueries({ queryKey: ['conversation', activeConversationId] })
    },
    onError: (error) =>
      setAttachError(error instanceof ApiError ? error.message : 'Could not attach documents.'),
  })

  // Close on Escape.
  useEffect(() => {
    if (!documentsOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDocumentsOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [documentsOpen, setDocumentsOpen])

  const attachedIds = new Set((conversation?.documents ?? []).map((doc) => doc.id))

  const uploadFiles = async (files: File[]) => {
    setLocalError(null)
    if (files.length === 0) return
    const notPdf = files.find((file) => !file.name.toLowerCase().endsWith('.pdf'))
    if (notPdf) {
      setLocalError(`${notPdf.name} is not a PDF.`)
      return
    }
    const tooBig = files.find((file) => file.size > MAX_BYTES)
    if (tooBig) {
      setLocalError(`${tooBig.name} is larger than 20 MB.`)
      return
    }
    try {
      for (const file of files) {
        await uploadMutation.mutateAsync(file)
      }
    } catch (error) {
      setLocalError(error instanceof ApiError ? error.message : 'Upload failed.')
    }
  }

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    setDragging(false)
    void uploadFiles(Array.from(event.dataTransfer.files))
  }

  const toggleSelected = (id: string) =>
    setSelected((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id],
    )

  if (!documentsOpen) return null

  return (
    <div className="drawer-layer">
      <div
        className="drawer-scrim"
        role="presentation"
        onClick={() => setDocumentsOpen(false)}
      />
      <aside className="drawer" aria-label="Documents">
        <header className="drawer-header">
          <h2 className="drawer-title">Documents</h2>
          <button
            className="btn btn-ghost drawer-close"
            type="button"
            onClick={() => setDocumentsOpen(false)}
            aria-label="Close documents"
          >
            ✕
          </button>
        </header>

        <div className="drawer-body">
          <div
            className={`dropzone${dragging ? ' dragging' : ''}`}
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            onClick={() => fileInputRef.current?.click()}
            role="button"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') fileInputRef.current?.click()
            }}
          >
            <p className="dropzone-title">Drop a PDF here</p>
            <p className="dropzone-hint">or click to browse · up to 20 MB</p>
            <input
              ref={fileInputRef}
              className="dropzone-input"
              type="file"
              accept="application/pdf,.pdf"
              multiple
              onChange={(event) => {
                void uploadFiles(Array.from(event.target.files ?? []))
                event.target.value = ''
              }}
            />
          </div>

          {localError && <p className="drawer-error">{localError}</p>}
          {uploadMutation.isPending && <p className="drawer-note">Uploading…</p>}

          {activeConversationId && (
            <section className="drawer-section">
              <h3 className="drawer-heading">In this chat</h3>
              {(conversation?.documents ?? []).length === 0 ? (
                <p className="drawer-note">No documents attached yet.</p>
              ) : (
                <ul className="attached-list">
                  {conversation?.documents.map((doc) => (
                    <li key={doc.id} className="attached-item">
                      <span className="attached-check" aria-hidden="true">
                        ✓
                      </span>
                      {doc.filename}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}

          <section className="drawer-section">
            <h3 className="drawer-heading">Library</h3>
            {documents.length === 0 ? (
              <p className="drawer-note">No documents uploaded yet.</p>
            ) : (
              <ul className="document-list">
                {documents.map((doc) => (
                  <DocumentRow
                    key={doc.id}
                    document={doc}
                    selectable={!!activeConversationId && isReady(doc.status) && !attachedIds.has(doc.id)}
                    attached={attachedIds.has(doc.id)}
                    selected={selected.includes(doc.id)}
                    onToggle={() => toggleSelected(doc.id)}
                  />
                ))}
              </ul>
            )}
          </section>

          {attachError && <p className="drawer-error">{attachError}</p>}
        </div>

        {activeConversationId && (
          <footer className="drawer-footer">
            <button
              className="btn btn-primary drawer-attach"
              type="button"
              disabled={selected.length === 0 || attachMutation.isPending}
              onClick={() => attachMutation.mutate(selected)}
            >
              {attachMutation.isPending
                ? 'Attaching…'
                : selected.length > 0
                  ? `Attach ${selected.length} document${selected.length > 1 ? 's' : ''}`
                  : 'Attach documents'}
            </button>
          </footer>
        )}
      </aside>
    </div>
  )
}

function DocumentRow({
  document,
  selectable,
  attached,
  selected,
  onToggle,
}: {
  document: DocumentListItem
  selectable: boolean
  attached: boolean
  selected: boolean
  onToggle: () => void
}) {
  const processing = isProcessing(document.status)
  const step = statusStep(document.status)
  const percent = document.status === 'READY' ? 100 : Math.round((step / 6) * 100)

  return (
    <li className="document-row">
      <div className="document-main">
        {selectable && (
          <input
            type="checkbox"
            className="document-check"
            checked={selected}
            onChange={onToggle}
            aria-label={`Select ${document.filename}`}
          />
        )}
        <span className="document-name" title={document.filename}>
          {document.filename}
        </span>
        <StatusPill status={document.status} />
      </div>

      {processing && (
        <div className="document-progress" aria-hidden="true">
          <span style={{ width: `${Math.max(percent, 5)}%` }} />
        </div>
      )}

      <p className="document-meta">
        {attached && <span className="document-attached">Attached</span>}
        {document.pageCount != null && <span>{document.pageCount} pages</span>}
        {document.chunkCount != null && <span>{document.chunkCount} chunks</span>}
        {processing && <span>{statusLabel(document.status)}…</span>}
      </p>

      {isFailed(document.status) && <FailedReason id={document.id} />}
    </li>
  )
}

function StatusPill({ status }: { status: DocumentStatus }) {
  const tone = isReady(status) ? 'ready' : isFailed(status) ? 'failed' : status === 'DELETED' ? 'muted' : 'busy'
  return <span className={`status-pill status-${tone}`}>{statusLabel(status)}</span>
}

/** Lazily fetches the failure detail for a FAILED document. */
function FailedReason({ id }: { id: string }) {
  const { data } = useQuery({
    queryKey: ['document', id],
    queryFn: () => documentsApi(api).status(id),
  })
  const reason = data?.error ?? data?.stage
  if (!reason) return null
  return <p className="document-failure">{reason}</p>
}
