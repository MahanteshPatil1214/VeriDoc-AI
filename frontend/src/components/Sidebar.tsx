import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, conversationsApi } from '../api'
import { useAuth } from '../auth/useAuth'
import { useWorkspace } from '../chat/useWorkspace'
import { formatRelative } from '../lib/time'
import './Sidebar.css'

export function Sidebar() {
  const { user, logout } = useAuth()
  const { activeConversationId, setActiveConversation, toggleDocuments } = useWorkspace()
  const queryClient = useQueryClient()

  const { data: conversations = [] } = useQuery({
    queryKey: ['conversations'],
    queryFn: () => conversationsApi(api).list(),
  })

  const createMutation = useMutation({
    mutationFn: () => conversationsApi(api).create('New chat'),
    onSuccess: (created) => {
      setActiveConversation(created.id)
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
  })

  const open = (id: string) => setActiveConversation(id)

  return (
    <aside className="sidebar">
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
        {conversations.length === 0 ? (
          <p className="conversations-empty">No conversations yet.</p>
        ) : (
          conversations.map((conversation) => (
            <button
              key={conversation.id}
              type="button"
              className={`conversation-item${conversation.id === activeConversationId ? ' active' : ''}`}
              onClick={() => open(conversation.id)}
            >
              <span className="conversation-title">{conversation.title}</span>
              <span className="conversation-time">
                {formatRelative(conversation.updatedAt)}
              </span>
            </button>
          ))
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
  )
}