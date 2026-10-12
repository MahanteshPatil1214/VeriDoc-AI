/** A parsed Server-Sent Event: normalized line endings, event type and raw data. */
export interface SseFrame {
  event: string
  data: string
}

/**
 * Parses a {@code text/event-stream} body incrementally. Yields one frame per
 * blank-line-delimited block. Works with the browser {@code fetch} streams the
 * backend emits (event:/data: lines, CRLF-tolerant).
 */
export async function* sseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let boundary: number
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const rawFrame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const frame = parseFrame(rawFrame)
      if (frame) yield frame
    }
  }

  const remainder = buffer.trim()
  if (remainder.length > 0) {
    const frame = parseFrame(remainder)
    if (frame) yield frame
  }
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  let data = ''
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      const value = line.slice(5).trim()
      data = data.length === 0 ? value : `${data}\n${value}`
    }
  }
  if (data.length === 0) {
    return null
  }
  return { event, data }
}