import type { TextMeasurer } from './prepare'

/** Height of `lines` lines the way Android measures them: `size + (lines - 1) * line`. */
export function textHeight(lines: number, size: number, line: number): number {
  return size + Math.max(0, lines - 1) * line
}

/** Browser measurement. Widths are the browser's; heights follow Android. Overflow still rejects the sheet. */
export function canvasTextMeasurer(doc: Document): TextMeasurer {
  const canvas = doc.createElement('canvas')
  const context = canvas.getContext('2d')
  return {
    measure(request) {
      if (!context) {
        return { width: request.maxWidth + 1, height: request.maxHeight + 1, hasVisualOverflow: true }
      }
      const style = `${request.italic ? 'italic ' : ''}${request.weight} ${request.size}px ${request.family}`
      context.font = style
      const lines = request.text.split('\n')
      let width = 0
      for (const line of lines) width = Math.max(width, context.measureText(line).width)
      // Android's Compose layout (CollageTextMeasurer, density 1): the first line is as tall as
      // the font size and each further line adds the line box. Measured on every bundled face,
      // size and line the layouts use; the recipient's fit checks must agree with the sender's.
      const height = textHeight(lines.length, request.size, request.line)
      const fittingLines = Math.max(1, Math.floor((request.maxHeight - request.size) / request.line) + 1)
      const hasVisualOverflow = request.softWrap === false
        ? width > request.maxWidth || height > request.maxHeight
        : width > request.maxWidth * fittingLines || height > request.maxHeight
      return { width: Math.ceil(width), height, hasVisualOverflow }
    },
  }
}
