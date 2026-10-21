import type { ReactNode } from 'react'

import type { Citation } from '../api'
import './Markdown.css'

interface MarkdownProps {
  content: string
  /** Render a caret at the end of the text while the answer is streaming. */
  streaming?: boolean
  /** Sources for this answer; enables inline `[n]` superscript references. */
  citations?: Citation[]
  activeCitation?: number | null
  onCitationClick?: (ordinal: number) => void
}

interface RenderContext {
  ordinals: Set<number>
  activeCitation?: number | null
  onCitationClick?: (ordinal: number) => void
}

/**
 * Minimal, dependency-free Markdown renderer for assistant answers. Supports
 * paragraphs, headings, fenced code, blockquotes, ordered/unordered lists and
 * inline code, bold and italic. Output is plain React nodes — never raw HTML.
 *
 * <p>When {@code citations} are supplied, `[1]` / `【1】` markers become
 * clickable superscripts that reveal the matching source below the answer.
 */
export function Markdown({
  content,
  streaming = false,
  citations = [],
  activeCitation,
  onCitationClick,
}: MarkdownProps) {
  const context: RenderContext = {
    ordinals: new Set(citations.map((citation) => citation.ordinal)),
    activeCitation,
    onCitationClick,
  }

  return (
    <div className="markdown">
      {renderBlocks(content, context)}
      {streaming && <span className="md-caret" aria-hidden="true" />}
    </div>
  )
}

function renderBlocks(source: string, context: RenderContext): ReactNode[] {
  const lines = source.replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    if (line.trim() === '') {
      i++
      continue
    }

    if (line.trimStart().startsWith('```')) {
      const code: string[] = []
      i++
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        code.push(lines[i])
        i++
      }
      i++
      blocks.push(
        <pre key={blocks.length}>
          <code>{code.join('\n')}</code>
        </pre>,
      )
      continue
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      blocks.push(renderHeading(heading[1].length, heading[2], blocks.length, context))
      i++
      continue
    }

    if (line.startsWith('> ')) {
      const quote: string[] = []
      while (i < lines.length && lines[i].startsWith('> ')) {
        quote.push(lines[i].slice(2))
        i++
      }
      blocks.push(
        <blockquote key={blocks.length}>{renderInline(quote.join(' '), context)}</blockquote>,
      )
      continue
    }

    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*]\s+/, ''))
        i++
      }
      blocks.push(
        <ul key={blocks.length}>
          {items.map((item, index) => (
            <li key={index}>{renderInline(item, context)}</li>
          ))}
        </ul>,
      )
      continue
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ''))
        i++
      }
      blocks.push(
        <ol key={blocks.length}>
          {items.map((item, index) => (
            <li key={index}>{renderInline(item, context)}</li>
          ))}
        </ol>,
      )
      continue
    }

    const paragraph: string[] = []
    while (i < lines.length && isParagraphLine(lines[i])) {
      paragraph.push(lines[i])
      i++
    }
    blocks.push(<p key={blocks.length}>{renderInline(paragraph.join(' '), context)}</p>)
  }

  return blocks
}

function isParagraphLine(line: string): boolean {
  return (
    line.trim() !== '' &&
    !line.trimStart().startsWith('```') &&
    !/^(#{1,6})\s+/.test(line) &&
    !line.startsWith('> ') &&
    !/^\s*[-*]\s+/.test(line) &&
    !/^\s*\d+\.\s+/.test(line)
  )
}

function renderHeading(
  level: number,
  text: string,
  key: number,
  context: RenderContext,
): ReactNode {
  const content = renderInline(text, context)
  if (level <= 1) return <h2 key={key}>{content}</h2>
  if (level === 2) return <h3 key={key}>{content}</h3>
  return <h4 key={key}>{content}</h4>
}

function renderInline(text: string, context: RenderContext): ReactNode[] {
  const nodes: ReactNode[] = []
  // Accept [1], 【1】 and the 【1†L5-L7】 form some models emit; all map to source 1.
  const pattern = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)|\[(\d{1,3})\]|【(\d{1,3})(?:†[^】]*)】/g
  let last = 0
  let key = 0
  let match = pattern.exec(text)

  while (match) {
    if (match.index > last) nodes.push(text.slice(last, match.index))
    const token = match[0]
    const ordinal = match[4] !== undefined ? Number(match[4])
      : match[5] !== undefined ? Number(match[5])
        : null

    if (token.startsWith('`')) {
      nodes.push(<code key={key++}>{token.slice(1, -1)}</code>)
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={key++}>{token.slice(2, -2)}</strong>)
    } else if (ordinal !== null) {
      if (context.ordinals.has(ordinal)) {
        nodes.push(
          <sup key={key++}>
            <button
              type="button"
              className={`citation-ref${context.activeCitation === ordinal ? ' active' : ''}`}
              aria-label={`Show source ${ordinal}`}
              title={`Source ${ordinal}`}
              onClick={() => context.onCitationClick?.(ordinal)}
            >
              {ordinal}
            </button>
          </sup>,
        )
      } else {
        // A marker the backend did not persist stays literal so nothing is invented.
        nodes.push(token)
      }
    } else {
      nodes.push(<em key={key++}>{token.slice(1, -1)}</em>)
    }
    last = match.index + token.length
    match = pattern.exec(text)
  }

  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}
