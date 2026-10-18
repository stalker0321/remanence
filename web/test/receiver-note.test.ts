import { describe, expect, it } from 'vitest'
import type { ResolvedExpression } from '../src/collage/model'
import { paintScene, type PaintImage } from '../src/collage/paint'
import { prepare, type Scene, type TextMeasurer } from '../src/collage/prepare'
import { pressSheet } from './sheets'

/**
 * T243 receiver frozen-note regression: a correctly frozen multi-line note
 * must be measured AND painted as the SAME wrapped rows in its stored
 * region/type (never a coarse single clipped line). The line-width
 * primitive is deterministic (10px per character), so the expected rows are
 * derived from the input, not from the wrapper itself.
 */
const LINE = 18
const SIZE = 16
const REGION_Y = 360
const REGION_X = 16

const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

const sources = new Map<string, { width: number; height: number }>()
for (const id of ['p1', 'p2', 'p3']) sources.set(id, { width: 64, height: 48 })

async function fixture(note: string | null, regionWidth: number, regionHeight: number): Promise<ResolvedExpression> {
  const base = await pressSheet('press')
  return {
    ...base,
    noteRegion: note === null ? null : { x: REGION_X, y: REGION_Y, width: regionWidth, height: regionHeight },
    musicRegion: null,
    input: { ...base.input, note, music: null },
  }
}

async function prepared(note: string | null, regionWidth: number, regionHeight: number) {
  const expression = await fixture(note, regionWidth, regionHeight)
  return prepare(expression, note, null, sources, measurer)
}

function readyScene(result: Awaited<ReturnType<typeof prepare>>): Scene {
  if (result.kind !== 'ready') throw new Error(`expected ready, got ${result.kind}: ${result.reason}`)
  return result.scene
}

describe('receiver frozen note wrapping', () => {
  it('lays out the note rows and baselines from the frozen region/type', async () => {
    const note = 'aa bb cc dd ee ff'
    const scene = readyScene(await prepared(note, 50, 3 * LINE))
    const layout = scene.note?.noteLayout
    expect(layout).toBeDefined()
    // Independently expected greedy wrap at width 50 (each line <= 5 chars).
    expect(layout?.lines.map((row) => row.text)).toEqual(['aa bb', 'cc dd', 'ee ff'])
    expect(layout?.lines.map((row) => row.baseline)).toEqual([
      REGION_Y + SIZE,
      REGION_Y + SIZE + LINE,
      REGION_Y + SIZE + 2 * LINE,
    ])
    expect(layout?.overflow).toBe(false)
  })

  it('preserves explicit breaks and empty lines as advanced rows', async () => {
    const scene = readyScene(await prepared('aa\n\nbb', 50, 3 * LINE))
    const layout = scene.note?.noteLayout
    expect(layout?.lines.map((row) => row.text)).toEqual(['aa', '', 'bb'])
    expect(layout?.lines[2]?.baseline).toBe(REGION_Y + SIZE + 2 * LINE)
  })

  it('preserves RU/UK words across wrapped rows', async () => {
    const scene = readyScene(await prepared('привіт світ сонце', 70, 3 * LINE))
    expect(scene.note?.noteLayout?.lines.map((row) => row.text)).toEqual(['привіт', 'світ', 'сонце'])
  })

  it('refuses an over-wide unbreakable word instead of clipping it as ready', async () => {
    const result = await prepared('aaaaaaaaaa', 50, 200)
    expect(result.kind).toBe('rejected')
    if (result.kind === 'rejected') expect(result.reason).toBe('note does not fit its sealed region')
  })

  it('refuses a note taller than its sealed region', async () => {
    const result = await prepared('aa bb cc dd ee ff', 50, LINE)
    expect(result.kind).toBe('rejected')
  })

  it('keeps the no-note case unchanged', async () => {
    const scene = readyScene(await prepared(null, 0, 0))
    expect(scene.note).toBeNull()
  })

  it('paints every row at its laid-out baseline (no single clipped line)', async () => {
    const scene = readyScene(await prepared('aa bb cc dd ee ff', 50, 3 * LINE))
    const fills: Array<{ text: string; x: number; y: number }> = []
    const context = new Proxy(
      {},
      {
        get: (_target, prop) => {
          if (prop === 'fillText') {
            return (text: string, x: number, y: number) => {
              fills.push({ text, x, y })
            }
          }
          return () => undefined
        },
        set: () => true,
      },
    ) as unknown as CanvasRenderingContext2D
    const images = new Map<string, PaintImage>()
    for (const id of ['p1', 'p2', 'p3']) images.set(id, { source: {} as CanvasImageSource, width: 120, height: 213 })
    expect(paintScene(context, scene, images, null)).toBe(true)
    expect(fills.map((row) => row.text)).toEqual(['aa bb', 'cc dd', 'ee ff'])
    expect(fills.map((row) => row.x)).toEqual([REGION_X, REGION_X, REGION_X])
    expect(fills.map((row) => row.y)).toEqual([
      REGION_Y + SIZE,
      REGION_Y + SIZE + LINE,
      REGION_Y + SIZE + 2 * LINE,
    ])
  })
})
