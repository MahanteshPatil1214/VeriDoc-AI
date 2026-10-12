import { Sidebar } from '../components/Sidebar'
import { ChatPane } from '../components/ChatPane'
import { DocumentsDrawer } from '../components/DocumentsDrawer'
import { useWorkspace } from '../chat/useWorkspace'
import './Workspace.css'

export function Workspace() {
  const toggleSidebar = useWorkspace((state) => state.toggleSidebar)

  return (
    <div className="workspace">
      <button
        className="mobile-menu"
        type="button"
        aria-label="Open conversations"
        onClick={toggleSidebar}
      >
        ☰
      </button>
      <Sidebar />
      <ChatPane />
      <DocumentsDrawer />
    </div>
  )
}