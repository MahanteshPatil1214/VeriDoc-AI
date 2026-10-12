import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { api, ApiError, conversationsApi } from '../api'
import type { ConversationListItem } from '../api'
import { useAuth } from '../auth/useAuth'
import { useWorkspace } from '../chat/useWorkspace'
import { formatRelative } from '../lib/time'
import './Sidebar.css'

export function Sidebar() {
  const { user, logout } = useAuth()
  const { activeConversationId, setActiveConversation, toggleDocuments, sidebarOpen, setSidebarOpen } =
    useWorkspace()
  const queryClient = useQueryClient()

  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameError, setRenameError] = useState<string | null>(null)
  const [deletePendingId, setDeletePendingId] = useState<string | null>(null)
  const [operationError, setOperationError] = useState<string | null>(null)

  const { data: conversations = [] } = useQuery({
    queryKey: ['conversations'],
    queryFn: () => conversationsApi(api).list(),
  })

  const createMutation = useMutation({
    mutationFn: () => conversationsApi(api).create('New chat'),
    onSuccess: (created) => {
      setActiveConversation(created.id)
      setSidebarOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
  })

  const renameMutation = useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      conversationsApi(api).rename(id, title),
    onSuccess: () => {
      setRenamingId(null)
      setRenameError(null)
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
    onError: (error) =>
      setRenameError(error instanceof ApiError ? error.message : 'Could not rename the conversation.'),
  })

  const open = (id: string) => {
    setActiveConversation(id)
    setSidebarOpen(false)
  }

  const commitRename = (id: string, rawTitle: string) => {
    const title = rawTitle.trim()
    if (title.length === 0) {
      setRenamingId(null)
      setRenameError(null)
      return
    }
    renameMutation.mutate({ id, title })
  }

  const deleteConversation = async (conversation: ConversationListItem) => {
    if (deletePendingId) return
    if (!window.confirm(`Delete "${conversation.title}"? The messages inside it will be removed.`)) return
    setDeletePendingId(conversation.id)
    try {
      await conversationsApi(api).remove(conversation.id)
      if (activeConversationId === conversation.id) setActiveConversation(null)
      if (renamingId === conversation.id) setRenamingId(null)
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      void queryClient.invalidateQueries({ queryKey: ['messages', conversation.id] })
    } catch (error) {
      setOperationError(error instanceof ApiError ? error.message : 'Could not delete the conversation.')
    } finally {
      setDeletePendingId(null)
    }
  }

  return (
    <>
      <div
        className={`sidebar-scrim${sidebarOpen ? ' show' : ''}`}
        role="presentation"
        onClick={() => setSidebarOpen(false)}
      />
      <aside className={`sidebar${sidebarOpen ? ' open' : ''}`}>
      <div className="sidebar-header">
        <div className="sidebar-brand">
          <span className="sidebar-logo" aria-hidden="true" />
          <span className="sidebar-name">VeriDoc AI</span>
        </div>
        <div className="sidebar-actions">
          <button
            className="btn btn-ghost sidebar-new"
            type="button"
            onClick={() => createMutation.mutate()}
            disabled={createMutation.isPending}
          >
            {createMutation.isPending ? 'Creating…' : '+ New chat'}
          </button>
          <button
            className="btn btn-ghost sidebar-docs"
            type="button"
            onClick={toggleDocuments}
            aria-label="Open documents"
          >
            Documents
          </button>
        </div>
      </div>

      <nav className="sidebar-conversations" aria-label="Conversations">
        {operationError && <p className="conversations-error">{operationError}</p>}
        {conversations.length === 0 ? (
          <p className="conversations-empty">No conversations yet.</p>
        ) : (
          conversations.map((conversation) =>
            renamingId === conversation.id ? (
              <RenameForm
                key={conversation.id}
                conversation={conversation}
                pending={renameMutation.isPending}
                error={renameError}
                onCommit={(title) => commitRename(conversation.id, title)}
                onCancel={() => {
                  setRenamingId(null)
                  setRenameError(null)
                }}
              />
            ) : (
              <div
                key={conversation.id}
                className={`conversation-row${conversation.id === activeConversationId ? ' active' : ''}`}
              >
                <button
                  type="button"
                  className="conversation-item"
                  onClick={() => open(conversation.id)}
                >
                  <span className="conversation-title">{conversation.title}</span>
                  <span className="conversation-time">
                    {formatRelative(conversation.updatedAt)}
                  </span>
                </button>
                <div className="conversation-actions">
                  <button
                    className="conversation-action"
                    type="button"
                    aria-label={`Rename ${conversation.title}`}
                    title="Rename"
                    onClick={() => {
                      setRenamingId(conversation.id)
                      setRenameError(null)
                    }}
                  >
                    ✎
                  </button>
                  <button
                    className="conversation-action"
                    type="button"
                    aria-label={`Delete ${conversation.title}`}
                    title="Delete"
                    disabled={deletePendingId === conversation.id}
                    onClick={() => void deleteConversation(conversation)}
                  >
                    ✕
                  </button>
                </div>
              </div>
            ),
          )
        )}
      </nav>

      <footer className="sidebar-footer">
        <div className="user-chip">
          <div className="user-avatar" aria-hidden="true">
            {user?.displayName.charAt(0).toUpperCase()}
          </div>
          <div className="user-meta">
            <span className="user-name">{user?.displayName}</span>
            <span className="user-email">{user?.email}</span>
          </div>
          <button className="btn btn-ghost user-signout" type="button" onClick={logout}>
            Sign out
          </button>
        </div>
      </footer>
      </aside>
    </>
  )
}

/** Inline title editor for a single conversation. */
function RenameForm({
  conversation,
  pending,
  error,
  onCommit,
  onCancel,
}: {
  conversation: ConversationListItem
  pending: boolean
  error: string | null
  onCommit: (title: string) => void
  onCancel: () => void
}) {
  const [title, setTitle] = useState(conversation.title)

  return (
    <form
      className="conversation-item conversation-rename"
      onSubmit={(event) => {
        event.preventDefault()
        onCommit(title)
      }}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) onCancel()
      }}
    >
      <input
        className="conversation-input"
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        disabled={pending}
        maxLength={255}
        autoFocus
        aria-label="Conversation title"
        onKeyDown={(event) => {
          if (event.key === 'Escape') onCancel()
        }}
      />
      {error && <p className="conversation-rename-error">{error}</p>}
    </form>
  )
}