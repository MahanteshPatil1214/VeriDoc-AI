import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'

import { api, ApiError, chatApi, conversationsApi } from '../api'
import type { Message, StreamEvent } from '../api'
import { useWorkspace } from '../chat/useWorkspace'
import { Composer } from './Composer'
import { MessageRow } from './Message'
import './ChatPane.css'

const SUGGESTIONS = [
  'Summarize the key points.',
  'What are the main obligations?',
  'List any deadlines or dates mentioned.',
  'What risks or warnings are called out?',
]

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
  const setPendingPrompt = useWorkspace((state) => state.setPendingPrompt)
  const { data: serverMessages = [] } = useQuery({
    queryKey: ['messages', conversationId],
    queryFn: () => chatApi(api).messages(conversationId),
  })

  /** Optimistic user row for the turn in flight. */
  const [pending, setPending] = useState<Message[]>([])
  /** The assistant row being streamed (mirrored in {@link draftRef} for commit). */
  const [draftAssistant, setDraftAssistant] = useState<Message | null>(null)
  const draftRef = useRef<Message | null>(null)
  const [error, setError] = useState<string | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const sendRef = useRef<((text: string) => Promise<void>) | null>(null)
  const initialPromptRef = useRef(useWorkspace.getState().pendingPrompt)

  const messages = useMemo(
    () => [...serverMessages, ...pending, ...(draftAssistant ? [draftAssistant] : [])],
    [serverMessages, pending, draftAssistant],
  )
  const busy = draftAssistant !== null

  const updateDraft = (patch: Partial<Message>) => {
    if (!draftRef.current) return
    draftRef.current = { ...draftRef.current, ...patch }
    setDraftAssistant({ ...draftRef.current })
  }

  const startDraft = (initial: Message) => {
    draftRef.current = initial
    setDraftAssistant(initial)
  }

  /** Push the finished user + assistant rows into the cached thread, then reconcile. */
  const commitTurn = (userTemp: Message, assistant: Message) => {
    queryClient.setQueryData<Message[]>(['messages', conversationId], (previous) => [
      ...(previous ?? []),
      { ...userTemp, status: 'COMPLETE' },
      assistant,
    ])
    setPending([])
    draftRef.current = null
    setDraftAssistant(null)
    void queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
    void queryClient.invalidateQueries({ queryKey: ['conversations'] })
  }

  // Abort an in-flight stream if the thread unmounts (e.g. switching chats).
  useEffect(() => {
    controllerRef.current = null
    return () => {
      controllerRef.current?.abort()
    }
  }, [conversationId])

  const send = async (text: string) => {
    setError(null)
    const controller = new AbortController()
    controllerRef.current = controller

    const userTemp: Message = {
      id: crypto.randomUUID(),
      role: 'USER',
      content: text,
      status: 'SENDING',
      createdAt: new Date().toISOString(),
    }
    setPending([userTemp])
    startDraft({
      id: crypto.randomUUID(),
      role: 'ASSISTANT',
      content: '',
      status: 'STREAMING',
      createdAt: new Date().toISOString(),
      citations: [],
    })

    try {
      const events = chatApi(api).streamAssistant(conversationId, text, controller.signal)
      for await (const event of events) {
        handleEvent(event)
      }
    } catch (err) {
      if (controller.signal.aborted) {
        updateDraft({ status: 'CANCELLED' })
      } else {
        updateDraft({ status: 'FAILED' })
        setError(err instanceof ApiError ? err.message : 'The answer could not be generated.')
      }
    } finally {
      const assistant = draftRef.current
      if (assistant && assistant.content.length === 0 && assistant.status === 'STREAMING') {
        updateDraft({ status: 'FAILED', content: 'No response was received.' })
      }
      if (draftRef.current) {
        commitTurn(userTemp, draftRef.current)
      }
      controllerRef.current = null
    }
  }

  useEffect(() => {
    sendRef.current = send
  })

  // Auto-send a prompt queued before this conversation existed (welcome suggestions).
  useEffect(() => {
    const prompt = initialPromptRef.current
    if (!prompt) return
    initialPromptRef.current = null
    setPendingPrompt(null)
    void sendRef.current?.(prompt)
  }, [setPendingPrompt])

  const handleEvent = (event: StreamEvent) => {
    switch (event.event) {
      case 'token':
        updateDraft({ content: (draftRef.current?.content ?? '') + event.text })
        break
      case 'citations':
        updateDraft({ citations: event.citations })
        break
      case 'done':
        updateDraft({
          id: event.reply.messageId,
          content: event.reply.content,
          status: event.reply.status,
          citations: event.reply.citations,
        })
        break
      case 'error':
        updateDraft({ status: 'FAILED' })
        setError(event.message)
        break
    }
  }

  const stop = () => {
    controllerRef.current?.abort()
  }

  const endRef = useRef<HTMLDivElement | null>(null)
  const lastMessageContent = messages[messages.length - 1]?.content
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages.length, lastMessageContent])

  return (
    <main className="chat-pane">
      <div className="thread">
        {messages.length === 0 && !busy && (
          <div className="chat-empty">
            <p className="chat-greeting">Ask anything about your documents.</p>
            <p className="chat-hint">
              Every answer is grounded in the PDFs you upload, with sources you can inspect.
            </p>
            <div className="suggestions">
              {SUGGESTIONS.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  className="suggestion"
                  onClick={() => void send(prompt)}
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((message) => (
          <MessageRow key={`${conversationId}-${message.id}`} message={message} />
        ))}
        {busy && draftAssistant?.content.length === 0 && (
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

      <Composer onSend={(text) => void send(text)} onStop={stop} busy={busy} />
    </main>
  )
}

function Welcome() {
  const queryClient = useQueryClient()
  const setActiveConversation = useWorkspace((state) => state.setActiveConversation)
  const setPendingPrompt = useWorkspace((state) => state.setPendingPrompt)

  const createMutation = useMutation({
    mutationFn: (title: string) => conversationsApi(api).create(title),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      setActiveConversation(created.id)
    },
    onError: () => setPendingPrompt(null),
  })

  const start = (prompt: string) => {
    setPendingPrompt(prompt)
    createMutation.mutate(prompt.length > 60 ? `${prompt.slice(0, 57)}…` : prompt)
  }

  return (
    <div className="chat-empty">
      <p className="chat-greeting">Ask anything about your documents.</p>
      <p className="chat-hint">
        Every answer is grounded in the PDFs you upload, with sources you can inspect.
      </p>
      <div className="suggestions">
        {SUGGESTIONS.map((prompt) => (
          <button
            key={prompt}
            type="button"
            className="suggestion"
            disabled={createMutation.isPending}
            onClick={() => start(prompt)}
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  )
}