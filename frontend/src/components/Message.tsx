import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'

import { api, feedbackApi } from '../api'
import type { Citation, FeedbackRating, Message } from '../api'
import { Markdown } from './Markdown'
import './Message.css'

interface MessageProps {
  message: Message
  conversationId: string
}

/** A single thread row. User turns are right-aligned; assistant turns are prose. */
export function MessageRow({ message, conversationId }: MessageProps) {
  const [activeCitation, setActiveCitation] = useState<number | null>(null)
  const isUser = message.role === 'USER'

  if (isUser) {
    return (
      <div className="msg msg-user">
        <div className="msg-user-bubble">{message.content}</div>
      </div>
    )
  }

  const streaming = message.status === 'STREAMING'
  const failed = message.status === 'FAILED' || message.status === 'CANCELLED'
  const citations = message.citations ?? []
  const lineRanges = markerLineRanges(message.content)
  const showCopy = !streaming && message.content.length > 0
  return (
    <div className="msg msg-assistant">
      <div className={`msg-prose${failed ? ' msg-error' : ''}`}>
        <Markdown
          content={message.content}
          streaming={streaming}
          citations={citations}
          activeCitation={activeCitation}
          onCitationClick={setActiveCitation}
        />
      </div>
      {citations.length > 0 && (
        <Sources
          citations={citations}
          lineRanges={lineRanges}
          activeOrdinal={activeCitation}
          onSelect={setActiveCitation}
        />
      )}
      {message.status === 'COMPLETE' ? (
        <FeedbackBar conversationId={conversationId} message={message} showCopy={showCopy} />
      ) : (
        showCopy && (
          <div className="msg-feedback">
            <div className="feedback-actions">
              <CopyButton text={message.content} />
            </div>
          </div>
        )
      )}
    </div>
  )
}

/**
 * Thumbs on a completed answer with an optional note. Re-rating is an upsert;
 * clicking the active thumb removes the rating.
 */
function FeedbackBar({
  conversationId,
  message,
  showCopy,
}: {
  conversationId: string
  message: Message
  showCopy: boolean
}) {
  const queryClient = useQueryClient()
  const [noteOpen, setNoteOpen] = useState(false)
  const [note, setNote] = useState(message.feedbackComment ?? '')
  const [saved, setSaved] = useState(false)

  const mutation = useMutation({
    mutationFn: async (input: {
      rating: FeedbackRating | null
      comment?: string | null
    }): Promise<void> => {
      if (input.rating === null) {
        await feedbackApi(api).clear(conversationId, message.id)
        return
      }
      await feedbackApi(api).submit(conversationId, message.id, {
        rating: input.rating,
        comment: input.comment ?? undefined,
      })
    },
    onSuccess: (_result, input) => {
      queryClient.setQueryData<Message[]>(['messages', conversationId], (previous) =>
        previous?.map((row) =>
          row.id === message.id
            ? {
                ...row,
                feedback: input.rating,
                feedbackComment: input.rating === null ? null : (input.comment ?? null),
              }
            : row,
        ),
      )
      if (input.rating === null) {
        setNote('')
        setNoteOpen(false)
      } else {
        setSaved(true)
      }
    },
  })

  useEffect(() => {
    if (!saved) return
    const timer = window.setTimeout(() => setSaved(false), 2000)
    return () => window.clearTimeout(timer)
  }, [saved])

  const current = message.feedback ?? null
  const noteId = `feedback-note-${message.id}`

  const choose = (rating: FeedbackRating) => {
    const next = current === rating ? null : rating
    mutation.mutate({ rating: next, comment: next === null ? null : note.trim() || null })
  }

  return (
    <div className="msg-feedback">
      <div className="feedback-actions">
        <button
          type="button"
          className={`feedback-btn${current === 'UP' ? ' active' : ''}`}
          aria-label="Helpful answer"
          aria-pressed={current === 'UP'}
          title="Helpful"
          disabled={mutation.isPending}
          onClick={() => choose('UP')}
        >
          <ThumbIcon />
        </button>
        <button
          type="button"
          className={`feedback-btn${current === 'DOWN' ? ' active' : ''}`}
          aria-label="Unhelpful answer"
          aria-pressed={current === 'DOWN'}
          title="Not helpful"
          disabled={mutation.isPending}
          onClick={() => choose('DOWN')}
        >
          <ThumbIcon down />
        </button>

        {current && !noteOpen && (
          <button
            type="button"
            className="feedback-note-toggle"
            onClick={() => setNoteOpen(true)}
          >
            {message.feedbackComment ? 'Edit note' : 'Add a note'}
          </button>
        )}

        {showCopy && <CopyButton text={message.content} />}

        {saved && <span className="feedback-saved">Saved</span>}
        {mutation.isError && (
          <span className="feedback-error" role="alert">
            Could not save feedback.
          </span>
        )}
      </div>

      {current && noteOpen && (
        <form
          className="feedback-note"
          onSubmit={(event) => {
            event.preventDefault()
            mutation.mutate({ rating: current, comment: note.trim() || null })
            setNoteOpen(false)
          }}
        >
          <input
            id={noteId}
            className="input"
            type="text"
            maxLength={2000}
            placeholder="What was wrong or especially good? (optional)"
            aria-label="Feedback note"
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          <button className="btn btn-secondary" type="submit" disabled={mutation.isPending}>
            Send
          </button>
        </form>
      )}
    </div>
  )
}

/** One-click copy of an answer, with a textarea fallback for non-secure origins. */
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(false), 1800)
    return () => window.clearTimeout(timer)
  }, [copied])

  const copy = async () => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        const area = document.createElement('textarea')
        area.value = text
        area.style.position = 'fixed'
        area.style.opacity = '0'
        document.body.appendChild(area)
        area.select()
        document.execCommand('copy')
        document.body.removeChild(area)
      }
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <button
      type="button"
      className={`feedback-btn feedback-copy${copied ? ' active' : ''}`}
      aria-label={copied ? 'Answer copied' : 'Copy answer'}
      title={copied ? 'Copied' : 'Copy answer'}
      onClick={() => void copy()}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
      <span>{copied ? 'Copied' : 'Copy'}</span>
    </button>
  )
}

/** Inline SVG so the control never depends on an emoji font being installed. */
function ThumbIcon({ down = false }: { down?: boolean }) {
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {down ? (
        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17" />
      ) : (
        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3" />
      )}
    </svg>
  )
}

function CopyIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  )
}

/**
 * Maps each source ordinal to the line span the model cited for it, e.g.
 * `【1†L19-L24】` becomes `lines 19–24` for source 1.
 */
function markerLineRanges(content: string): Map<number, string> {
  const ranges = new Map<number, string>()
  const pattern = /【(\d{1,3})†L(\d+)(?:-L?(\d+))?】/g
  let match = pattern.exec(content)
  while (match) {
    const ordinal = Number(match[1])
    if (!ranges.has(ordinal)) {
      const start = Number(match[2])
      const end = match[3] ? Number(match[3]) : start
      ranges.set(ordinal, start === end ? `line ${start}` : `lines ${start}–${end}`)
    }
    match = pattern.exec(content)
  }
  return ranges
}

/**
 * Compact source list: document name and the cited line span (or page). The
 * answer text is never repeated here.
 */
export function Sources({
  citations,
  lineRanges,
  activeOrdinal,
  onSelect,
}: {
  citations: Citation[]
  lineRanges: Map<number, string>
  activeOrdinal?: number | null
  onSelect?: (ordinal: number | null) => void
}) {
  const itemRefs = useRef<Record<number, HTMLButtonElement | null>>({})

  useEffect(() => {
    if (activeOrdinal == null) return
    itemRefs.current[activeOrdinal]?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [activeOrdinal])

  return (
    <div className="sources">
      <p className="sources-label">Sources</p>
      <div className="sources-chips">
        {citations.map((citation) => {
          const active = activeOrdinal === citation.ordinal
          const location =
            lineRanges.get(citation.ordinal) ??
            (citation.pageNumber != null ? `page ${citation.pageNumber}` : null)
          return (
            <button
              key={citation.ordinal}
              type="button"
              className={`source-chip${active ? ' open' : ''}`}
              ref={(node) => {
                itemRefs.current[citation.ordinal] = node
              }}
              onClick={() => onSelect?.(active ? null : citation.ordinal)}
            >
              <span className="source-chip-num">{citation.ordinal}</span>
              {citation.documentFilename}
              {location && <span className="source-chip-loc"> — {location}</span>}
            </button>
          )
        })}
      </div>
    </div>
  )
}
