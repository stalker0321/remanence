import type { Rect } from './model'
import { cssColor } from './styles'

/**
 * Creator-only wrapped note layout + painter (T236 r3).
 *
 * The plan grammar sizes a note REGION with a real measurer; the shared
 * receiver painter (`collage/paint.ts`) draws a note as a single clipped
 * `fillText`, which would hide every wrapped row. This module provides
 * the ONE canonical word-wrap used by BOTH the creator's region sizing
 * and its actual painting, so the measured layout and the drawn rows are
 * the same algorithm and the same face — never two wrappers.
 *
 * The shared receiver single-line note painter is intentionally left
 * untouched (documented follow-up), and this is not a sealed expression
 * or publish authorization.
 */

export interface CreatorNoteFace {
  font: string
  weight: number
  italic: boolean
  size: number
  line: number
}

export interface WrappedNote {
  lines: string[]
  overflow: boolean
}

/** Same CSS font string the shared painter builds for a scene note. */
export function noteFontOf(face: Pick<CreatorNoteFace, 'font' | 'weight' | 'italic' | 'size'>): string {
  return `${face.italic ? 'italic' : 'normal'} ${face.weight} ${face.size}px ${face.font}`
}

/**
 * Greedy word wrap. Explicit `\n` breaks and empty lines are preserved.
 * A single word wider than `maxWidth` is kept on its own line and sets
 * `overflow`, so the caller fails closed instead of clipping it as ready.
 */
export function wrapNoteLines(
  text: string,
  maxWidth: number,
  measureLineWidth: (line: string) => number,
): WrappedNote {
  const width = Number.isFinite(maxWidth) && maxWidth > 0 ? maxWidth : 0
  const lines: string[] = []
  let overflow = width === 0
  for (const paragraph of text.split('\n')) {
    if (paragraph === '') {
      lines.push('')
      continue
    }
    const words = paragraph.split(/\s+/).filter((word) => word.length > 0)
    let current = ''
    for (const word of words) {
      const candidate = current === '' ? word : `${current} ${word}`
      if (measureLineWidth(candidate) <= width) {
        current = candidate
        continue
      }
      if (current === '') {
        overflow = true
        current = word
      } else {
        lines.push(current)
        current = word
      }
    }
    if (current !== '') lines.push(current)
  }
  if (lines.length === 0) lines.push('')
  return { lines, overflow }
}

export interface CreatorNoteLine {
  text: string
  width: number
  /** Baseline y within the canvas; matches the shared painter's rows. */
  baseline: number
}

export interface CreatorNoteLayout {
  lines: CreatorNoteLine[]
  lineHeight: number
  width: number
  height: number
  overflow: boolean
}

/**
 * Full layout for painting: the wrapped lines, their widths, and the
 * baseline of each row (`region.y + size + index*line`, the same row
 * geometry the shared painter uses). `overflow` is true when any line
 * exceeds the region width or the rows exceed the region height.
 */
export function layoutCreatorNote(
  text: string,
  face: CreatorNoteFace,
  region: Rect,
  measureLineWidth: (line: string) => number,
): CreatorNoteLayout {
  const wrapped = wrapNoteLines(text, region.width, measureLineWidth)
  const lines = wrapped.lines.map((line, index) => ({
    text: line,
    width: measureLineWidth(line),
    baseline: region.y + face.size + index * face.line,
  }))
  const width = lines.reduce((max, line) => Math.max(max, line.width), 0)
  const height = lines.length * face.line
  const overflow = wrapped.overflow || width > region.width + 1e-6 || height > region.height + 1e-6
  return { lines, lineHeight: face.line, width, height, overflow }
}

/** Draw every laid-out row inside the region clip (empty rows advance only). */
export function paintCreatorNote(
  context: CanvasRenderingContext2D,
  layout: CreatorNoteLayout,
  face: CreatorNoteFace,
  region: Rect,
  color: number,
): void {
  context.save()
  context.beginPath()
  context.rect(region.x, region.y, region.width, region.height)
  context.clip()
  context.fillStyle = cssColor(color)
  context.font = noteFontOf(face)
  for (const line of layout.lines) {
    if (line.text === '') continue
    context.fillText(line.text, region.x, line.baseline)
  }
  context.restore()
}
