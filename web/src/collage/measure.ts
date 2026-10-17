import type { TextMeasurer } from './prepare'

/** Browser measurement. It is not Android pixel equality; overflow still rejects the sheet. */
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
      const height = Math.max(request.line, lines.length * request.line)
      const hasVisualOverflow = request.softWrap === false
        ? width > request.maxWidth || height > request.maxHeight
        : width > request.maxWidth * Math.max(1, Math.floor(request.maxHeight / request.line)) || height > request.maxHeight
      return { width: Math.ceil(width), height, hasVisualOverflow }
    },
  }
}
