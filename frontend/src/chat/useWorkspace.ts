import { create } from 'zustand'

interface WorkspaceState {
  /** The conversation whose thread is open, or null for the welcome state. */
  activeConversationId: string | null
  setActiveConversation: (id: string | null) => void
}

export const useWorkspace = create<WorkspaceState>()((set) => ({
  activeConversationId: null,
  setActiveConversation: (id) => set({ activeConversationId: id }),
}))