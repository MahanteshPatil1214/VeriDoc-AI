import { Sidebar } from '../components/Sidebar'
import { ChatPane } from '../components/ChatPane'
import './Workspace.css'

export function Workspace() {
  return (
    <div className="workspace">
      <Sidebar />
      <ChatPane />
    </div>
  )
}