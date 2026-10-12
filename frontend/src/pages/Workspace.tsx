import { Sidebar } from '../components/Sidebar'
import { ChatPane } from '../components/ChatPane'
import { DocumentsDrawer } from '../components/DocumentsDrawer'
import './Workspace.css'

export function Workspace() {
  return (
    <div className="workspace">
      <Sidebar />
      <ChatPane />
      <DocumentsDrawer />
    </div>
  )
}