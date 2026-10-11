import { useAuth } from './auth/useAuth'
import { AuthPage } from './pages/AuthPage'
import { Workspace } from './pages/Workspace'
import './App.css'

export function App() {
  const { isAuthenticated, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="boot">
        <div className="boot-spinner" aria-hidden="true" />
        <p>Loading VeriDoc AI…</p>
      </div>
    )
  }

  return isAuthenticated ? <Workspace /> : <AuthPage />
}