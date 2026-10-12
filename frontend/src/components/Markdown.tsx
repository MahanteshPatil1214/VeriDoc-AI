import type { ReactNode } from 'react'

import './Markdown.css'

interface MarkdownProps {
  content: string
  /** Render a caret at the end of the text while the answer is streaming. */
  streaming?: boolean
}

/**
 * Minimal, dependency-free Markdown renderer for assistant answers. Supports
 * paragraphs, headings, fenced code, blockquotes, ordered/unordered lists and
 * inline code, bold and italic. Output is plain React nodes — never raw HTML.
 */
export function Markdown({ content, streaming = false }: MarkdownProps) {
  return (
    <div className="markdown">
      {renderBlocks(content)}
      {streaming && <span className="md-caret" aria-hidden="true" />}
    </div>
  )
}

function renderBlocks(source: string): ReactNode[] {
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
      blocks.push(renderHeading(heading[1].length, heading[2], blocks.length))
      i++
      continue
    }

    if (line.startsWith('> ')) {
      const quote: string[] = []
      while (i < lines.length && lines[i].startsWith('> ')) {
        quote.push(lines[i].slice(2))
        i++
      }
      blocks.push(<blockquote key={blocks.length}>{renderInline(quote.join(' '))}</blockquote>)
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
            <li key={index}>{renderInline(item)}</li>
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
            <li key={index}>{renderInline(item)}</li>
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
    blocks.push(<p key={blocks.length}>{renderInline(paragraph.join(' '))}</p>)
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

function renderHeading(level: number, text: string, key: number): ReactNode {
  const content = renderInline(text)
  if (level <= 1) return <h2 key={key}>{content}</h2>
  if (level === 2) return <h3 key={key}>{content}</h3>
  return <h4 key={key}>{content}</h4>
}

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  const pattern = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)/g
  let last = 0
  let key = 0
  let match = pattern.exec(text)

  while (match) {
    if (match.index > last) nodes.push(text.slice(last, match.index))
    const token = match[0]
    if (token.startsWith('`')) {
      nodes.push(<code key={key++}>{token.slice(1, -1)}</code>)
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={key++}>{token.slice(2, -2)}</strong>)
    } else {
      nodes.push(<em key={key++}>{token.slice(1, -1)}</em>)
    }
    last = match.index + token.length
    match = pattern.exec(text)
  }

  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}
