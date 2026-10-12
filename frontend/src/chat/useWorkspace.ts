import { create } from 'zustand'

interface WorkspaceState {
  /** The conversation whose thread is open, or null for the welcome state. */
  activeConversationId: string | null
  setActiveConversation: (id: string | null) => void

  /** Whether the document library drawer is open. */
  documentsOpen: boolean
  setDocumentsOpen: (open: boolean) => void
  toggleDocuments: () => void

  /** Whether the off-canvas conversation sidebar is open (small screens). */
  sidebarOpen: boolean
  setSidebarOpen: (open: boolean) => void
  toggleSidebar: () => void

  /** A prompt to auto-send once a freshly created conversation mounts. */
  pendingPrompt: string | null
  setPendingPrompt: (prompt: string | null) => void
}

export const useWorkspace = create<WorkspaceState>()((set) => ({
  activeConversationId: null,
  setActiveConversation: (id) => set({ activeConversationId: id }),

  documentsOpen: false,
  setDocumentsOpen: (documentsOpen) => set({ documentsOpen }),
  toggleDocuments: () => set((state) => ({ documentsOpen: !state.documentsOpen })),

  sidebarOpen: false,
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
  toggleSidebar: () => set((state) => ({ sidebarOpen: !state.sidebarOpen })),

  pendingPrompt: null,
  setPendingPrompt: (pendingPrompt) => set({ pendingPrompt }),
}))
