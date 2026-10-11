import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'

import { api, ApiError, chatApi } from '../api'
import type { ChatReply, Message } from '../api'
import { useWorkspace } from '../chat/useWorkspace'
import { Composer } from './Composer'
import { MessageRow } from './Message'
import './ChatPane.css'

export function ChatPane() {
  const { activeConversationId } = useWorkspace()

  if (!activeConversationId) {
    return (
      <main className="chat-pane">
        <Welcome />
      </main>
    )
  }
  return <Thread key={activeConversationId} conversationId={activeConversationId} />
}

function Thread({ conversationId }: { conversationId: string }) {
  const queryClient = useQueryClient()
  const { data: serverMessages = [] } = useQuery({
    queryKey: ['messages', conversationId],
    queryFn: () => chatApi(api).messages(conversationId),
  })

  // Optimistic/in-flight rows for the current turn. Cleared once the server
  // acknowledges the reply and the cache is updated + invalidated.
  const [pending, setPending] = useState<Message[]>([])
  const [error, setError] = useState<string | null>(null)
  const pendingTemp = useRef<Message | null>(null)

  const messages = useMemo(() => [...serverMessages, ...pending], [serverMessages, pending])

  const askMutation = useMutation({
    mutationFn: (message: string) => chatApi(api).ask(conversationId, message),
    onSuccess: (reply: ChatReply) => {
      // Keep the optimistic user message visible and append the reply immediately;
      // the invalidated refetch then reconciles with the server's rows.
      const optimisticUser = pendingTemp.current
      queryClient.setQueryData<Message[]>(['messages', conversationId], (previous) => {
        const base = previous ?? []
        const existingUser = optimisticUser
          ? base.find((m) => m.id === optimisticUser.id)
          : undefined
        if (existingUser) return [...base, assistantFromReply(reply)]
        const rows = optimisticUser ? [...base, { ...optimisticUser, status: 'COMPLETE' }] : [...base]
        return [...rows, assistantFromReply(reply)]
      })
      setPending([])
      pendingTemp.current = null
      setError(null)
      void queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
    onError: (err: unknown) => {
      setPending([])
      pendingTemp.current = null
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.')
    },
  })

  const busy = askMutation.isPending

  const send = (text: string) => {
    setError(null)
    const temp: Message = {
      id: crypto.randomUUID(),
      role: 'USER',
      content: text,
      status: 'SENDING',
      createdAt: new Date().toISOString(),
    }
    pendingTemp.current = temp
    setPending([temp])
    askMutation.mutate(text)
  }

  const endRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages.length, busy])

  return (
    <main className="chat-pane">
      <div className="thread">
        {messages.map((message) => (
          <MessageRow key={message.id} message={message} />
        ))}
        {busy && (
          <div className="msg msg-assistant">
            <span className="typing" aria-label="thinking">
              <span className="typing-dot" />
              <span className="typing-dot" />
              <span className="typing-dot" />
            </span>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {error && (
        <p className="chat-error" role="alert">
          {error}
        </p>
      )}

      <Composer onSend={send} busy={busy} />
    </main>
  )
}

function Welcome() {
  return (
    <div className="chat-empty">
      <p className="chat-greeting">Ask anything about your documents.</p>
      <p className="chat-hint">
        Every answer is grounded in the PDFs you upload, with sources you can inspect.
      </p>
    </div>
  )
}

function assistantFromReply(reply: ChatReply): Message {
  return {
    id: reply.messageId,
    role: reply.role,
    content: reply.content,
    status: reply.status,
    createdAt: reply.createdAt,
  }
}