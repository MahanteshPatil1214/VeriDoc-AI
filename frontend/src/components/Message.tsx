import type { Message } from '../api'
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
  return (
    <div className="msg msg-assistant">
      <div className={`msg-prose${failed ? ' msg-error' : ''}`}>{message.content}</div>
    </div>
  )
}