import { create } from 'zustand'

interface WorkspaceState {
  /** The conversation whose thread is open, or null for the welcome state. */
  activeConversationId: string | null
  setActiveConversation: (id: string | null) => void

  /** Whether the document library drawer is open. */
  documentsOpen: boolean
  setDocumentsOpen: (open: boolean) => void
  toggleDocuments: () => void
}

export const useWorkspace = create<WorkspaceState>()((set) => ({
  activeConversationId: null,
  setActiveConversation: (id) => set({ activeConversationId: id }),

  documentsOpen: false,
  setDocumentsOpen: (documentsOpen) => set({ documentsOpen }),
  toggleDocuments: () => set((state) => ({ documentsOpen: !state.documentsOpen })),
}))
