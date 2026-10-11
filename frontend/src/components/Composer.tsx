import { useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'

import './Composer.css'

interface ComposerProps {
  onSend: (text: string) => void
  busy: boolean
}

export function Composer({ onSend, busy }: ComposerProps) {
  const [value, setValue] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const text = value.trim()
    if (!text || busy) return
    onSend(text)
    setValue('')
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      submit(event)
    }
  }

  return (
    <form className="composer" onSubmit={submit}>
      <div className="composer-box">
        <textarea
          className="composer-input"
          rows={1}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Ask anything about your documents…"
          autoFocus
        />
        <button
          className="btn btn-primary composer-send"
          type="submit"
          disabled={busy || value.trim().length === 0}
          aria-label="Send message"
        >
          {busy ? 'Sending…' : 'Send'}
        </button>
      </div>
      <p className="composer-hint">Press Enter to send, Shift+Enter for a new line.</p>
    </form>
  )
}