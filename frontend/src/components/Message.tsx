import { useState } from 'react'

import type { Citation, Message } from '../api'
import { Markdown } from './Markdown'
import './Message.css'

interface MessageProps {
  message: Message
}

/** A single thread row. User turns are right-aligned; assistant turns are prose. */
export function MessageRow({ message }: MessageProps) {
  const isUser = message.role === 'USER'

  if (isUser) {
    return (
      <div className="msg msg-user">
        <div className="msg-user-bubble">{message.content}</div>
      </div>
    )
  }

  const failed = message.status === 'FAILED' || message.status === 'CANCELLED'
  const citations = message.citations ?? []
  return (
    <div className="msg msg-assistant">
      <div className={`msg-prose${failed ? ' msg-error' : ''}`}>
        <Markdown content={message.content} streaming={message.status === 'STREAMING'} />
      </div>
      {citations.length > 0 && <Sources citations={citations} />}
    </div>
  )
}

/** Expandable citation chips: click a source to reveal the quoted text. */
export function Sources({ citations }: { citations: Citation[] }) {
  const [expanded, setExpanded] = useState<number[]>([])

  const toggle = (ordinal: number) => {
    setExpanded((current) =>
      current.includes(ordinal) ? current.filter((o) => o !== ordinal) : [...current, ordinal],
    )
  }

  return (
    <div className="sources">
      <p className="sources-label">Sources</p>
      <div className="sources-chips">
        {citations.map((citation) => {
          const isOpen = expanded.includes(citation.ordinal)
          return (
            <div key={citation.ordinal} className="source">
              <button
                type="button"
                className={`source-chip${isOpen ? ' open' : ''}`}
                onClick={() => toggle(citation.ordinal)}
                aria-expanded={isOpen}
              >
                <span className="source-chip-num">{citation.ordinal}</span>
                {citation.documentFilename}
                {citation.pageNumber != null && <span> · p.{citation.pageNumber}</span>}
              </button>
              {isOpen && (
                <div className="source-quote">
                  {citation.section && <p className="source-meta">Section: {citation.section}</p>}
                  {citation.relevanceScore != null && (
                    <p className="source-meta">
                      Relevance: {Math.round(citation.relevanceScore * 100)}%
                    </p>
                  )}
                  <blockquote className="source-quoted">{citation.quotedText}</blockquote>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}