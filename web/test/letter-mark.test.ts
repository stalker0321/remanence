import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { letterMarkPlace } from '../src/collage/CardBack'
import type { SceneText } from '../src/collage/prepare'

function note(region: { x: number; y: number; width: number; height: number }, size: number, line: number, widths: number[]): SceneText {
  return {
    text: 'x',
    region,
    family: 'StudySerif',
    size,
    weight: 400,
    italic: false,
    line,
    color: 0xff000000,
    noteLayout: {
      lines: widths.map((width, index) => ({ text: width === 0 ? '' : 'x', width, baseline: region.y + size + index * line })),
      lineHeight: line,
      width: Math.max(...widths),
      height: widths.length * line,
      overflow: false,
    },
  }
}

describe('letter mark place', () => {
  const region = { x: 16, y: 573, width: 328, height: 52 }

  it('sits right after the last word when the line has room', () => {
    const place = letterMarkPlace(note(region, 20, 26, [320, 140]))
    expect(place.backed).toBe(false)
    expect(place.size).toBe(18)
    expect(place.x).toBe(16 + 140 + 6)
    // Centred a little above the last row's baseline.
    expect(place.y + place.size / 2).toBeCloseTo(573 + 20 + 26 - 6, 5)
  })

  it('takes the end of a full last line on a patch of paper', () => {
    const place = letterMarkPlace(note(region, 20, 26, [320, 318]))
    expect(place.backed).toBe(true)
    expect(place.x).toBe(16 + 328 - 18)
  })

  it('follows the last row that has words', () => {
    const place = letterMarkPlace(note(region, 20, 26, [100, 0]))
    expect(place.x).toBe(16 + 100 + 6)
    expect(place.y + place.size / 2).toBeCloseTo(573 + 20 - 6, 5)
  })

  it('keeps its size between 12 and 18 px', () => {
    expect(letterMarkPlace(note(region, 8, 12, [50])).size).toBe(12)
    expect(letterMarkPlace(note(region, 13, 17, [50])).size).toBe(14)
    expect(letterMarkPlace(note(region, 40, 48, [50])).size).toBe(18)
  })

  it('stays inside the note region of every reference plan, whatever the last line holds', () => {
    const rows = readFileSync(new URL('./v5-plans-reference.txt', import.meta.url), 'utf8').split('\n')
    let checked = 0
    for (const row of rows) {
      if (!row.startsWith('PLAN\t')) continue
      const fields = row.split('\t')
      const noteField = fields[fields.indexOf('NOTE') + 1]!
      if (noteField === '-') continue
      const [x, y, width, height] = noteField.split(':')[1]!.split(',').map(Number) as [number, number, number, number]
      const box = { x, y, width, height }
      for (const size of [14, 20, 28]) {
        if (size > height) continue
        const line = Math.round(size * 1.3)
        const lines = Math.max(1, Math.floor((height - size) / line) + 1)
        for (const last of [1, width / 2, width - 10, width]) {
          const place = letterMarkPlace(note(box, size, line, [...Array<number>(lines - 1).fill(width), last]))
          expect(place.x).toBeGreaterThanOrEqual(x)
          expect(place.x + place.size).toBeLessThanOrEqual(x + width + 1e-6)
          expect(place.y).toBeGreaterThanOrEqual(y)
          if (place.size <= height) expect(place.y + place.size).toBeLessThanOrEqual(y + height + 1e-6)
          checked++
        }
      }
    }
    expect(checked).toBeGreaterThan(1000)
  })
})
