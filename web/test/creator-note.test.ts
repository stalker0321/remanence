import { describe, expect, it } from 'vitest'
import { layoutCreatorNote, paintCreatorNote, wrapNoteLines, type CreatorNoteFace } from '../src/collage/creatorNote'

/** Deterministic 10px/glyph line-width primitive so wrap math is exact. */
const width = (line: string): number => line.length * 10

const face: CreatorNoteFace = { font: 'StudySans', weight: 400, italic: false, size: 20, line: 25 }

describe('creator note wrap (one shared algorithm)', () => {
  it('greedily wraps words at the width', () => {
    expect(wrapNoteLines('aa bb cc', 50, width)).toEqual({ lines: ['aa bb', 'cc'], overflow: false })
  })

  it('preserves explicit breaks and empty lines', () => {
    expect(wrapNoteLines('a\n\nb', 100, width)).toEqual({ lines: ['a', '', 'b'], overflow: false })
  })

  it('flags a single over-wide word as overflow instead of hiding it', () => {
    expect(wrapNoteLines('aaaaaaaaaa', 25, width)).toEqual({ lines: ['aaaaaaaaaa'], overflow: true })
  })

  it('never drops words for a normal wrap', () => {
    const text = 'one two three four five six'
    const wrapped = wrapNoteLines(text, 70, width)
    expect(wrapped.overflow).toBe(false)
    expect(wrapped.lines.join(' ').split(/\s+/)).toEqual(text.split(/\s+/))
    expect(wrapped.lines.length).toBeGreaterThan(1)
  })
})

describe('creator note layout', () => {
  const region = { x: 16, y: 500, width: 70, height: 200 }

  it('lays out rows with baseline = region.y + size + index*line', () => {
    const layout = layoutCreatorNote('one two three four five six', face, region, width)
    expect(layout.overflow).toBe(false)
    expect(layout.lines.map((line) => line.baseline)).toEqual(layout.lines.map((_line, index) => 500 + 20 + index * 25))
  })

  it('overflows when a row is wider than the region or rows exceed its height', () => {
    expect(layoutCreatorNote('aaaaaaaaaa', face, region, width).overflow).toBe(true)
    const tall = { ...region, height: 30 }
    expect(layoutCreatorNote('one two three four five six', face, tall, width).overflow).toBe(true)
  })
})

describe('creator note painter', () => {
  it('draws every non-empty row at its baseline inside the region clip', () => {
    const calls: Array<{ text: string; x: number; y: number }> = []
    const context = {
      save() {},
      restore() {},
      beginPath() {},
      rect() {},
      clip() {},
      fillText(text: string, x: number, y: number) {
        calls.push({ text, x, y })
      },
      set fillStyle(_value: string) {},
      set font(_value: string) {},
    } as unknown as CanvasRenderingContext2D
    const region = { x: 16, y: 500, width: 70, height: 100 }
    const layout = layoutCreatorNote('one two three four five six', face, region, width)
    paintCreatorNote(context, layout, face, region, 0xff000000)
    expect(calls.map((call) => call.text)).toEqual(layout.lines.filter((line) => line.text !== '').map((line) => line.text))
    expect(calls.every((call) => call.x === 16)).toBe(true)
    expect(calls.map((call) => call.y)).toEqual(layout.lines.filter((line) => line.text !== '').map((line) => line.baseline))
  })
})
