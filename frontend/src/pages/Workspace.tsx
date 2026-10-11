import { useAuth } from '../auth/useAuth'
import './Workspace.css'

export function Workspace() {
  const { user, logout } = useAuth()

  return (
    <div className="workspace">
      <aside className="sidebar">
        <div className="sidebar-header">
          <div className="sidebar-brand">
            <span className="sidebar-logo" aria-hidden="true" />
            <span className="sidebar-name">VeriDoc AI</span>
          </div>
          <button className="btn btn-ghost sidebar-new" type="button">
            New chat
          </button>
        </div>

        <nav className="sidebar-conversations" aria-label="Conversations">
          <p className="conversations-empty">No conversations yet.</p>
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

      <main className="chat-pane">
        <div className="chat-empty">
          <p className="chat-greeting">Ask anything about your documents.</p>
          <p className="chat-hint">
            Upload a PDF and every answer will be grounded in it, with sources you can click.
          </p>
        </div>
      </main>
    </div>
  )
}