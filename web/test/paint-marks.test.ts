import { describe, expect, it } from 'vitest'
import { paintScene, type PaintImage } from '../src/collage/paint'
import { prepare, type Scene } from '../src/collage/prepare'
import { fitText, pressSheet } from './sheets'

/** Every glyph is 10 wide; fills and moves are recorded. */
function recorder() {
  const fills: Array<{ text: string; x: number; y: number }> = []
  const moves: Array<[string, ...number[]]> = []
  const context = new Proxy(
    {},
    {
      get: (_target, prop) => {
        if (prop === 'fillText') return (text: string, x: number, y: number) => fills.push({ text, x, y })
        if (prop === 'measureText') return (text: string) => ({ width: [...text].length * 10 })
        if (prop === 'translate' || prop === 'rotate') return (...args: number[]) => moves.push([prop, ...args])
        return () => undefined
      },
      set: () => true,
    },
  ) as unknown as CanvasRenderingContext2D
  return { context, fills, moves }
}

async function sceneWith(mark: Partial<Scene['provenance'][number]>) {
  const sources = new Map(['p1', 'p2', 'p3'].map((id) => [id, { width: 64, height: 48 }]))
  const ready = await prepare(await pressSheet(), 'hello', { title: 'Moon', artistDisplay: 'Ada' }, sources, fitText)
  if (ready.kind !== 'ready') throw new Error(ready.reason)
  const base: Scene['provenance'][number] = {
    kind: 'brand', text: 'ABCD', region: { x: 20, y: 600, width: 200, height: 24 },
    family: 'StudySans', size: 20, weight: 400, italic: false, line: 21,
    orientation: 'horizontal', align: 'left', color: 0xff000000, tracking: 0,
  }
  return { ...ready.scene, note: null, music: null, artwork: null, provenance: [{ ...base, ...mark }] }
}

function paint(scene: Scene) {
  const out = recorder()
  const images = new Map<string, PaintImage>()
  for (const id of ['p1', 'p2', 'p3']) images.set(id, { source: {} as CanvasImageSource, width: 64, height: 48 })
  expect(paintScene(out.context, scene, images, null)).toBe(true)
  return out
}

describe('provenance marks as Android draws them', () => {
  it('place the word by its sealed alignment in the free length of the box', async () => {
    // 4 glyphs of 10 in a 200-wide box leave 160 free.
    expect(paint(await sceneWith({ align: 'left' })).fills.at(-1)?.x).toBe(0)
    expect(paint(await sceneWith({ align: 'center' })).fills.at(-1)?.x).toBe(80)
    expect(paint(await sceneWith({ align: 'right' })).fills.at(-1)?.x).toBe(160)
  })

  it('align a vertical mark along its height', async () => {
    const { fills, moves } = paint(await sceneWith({ orientation: 'vertical-down', align: 'right', region: { x: 2, y: 8, width: 23, height: 300 } }))
    expect(moves.slice(-2)).toEqual([['translate', 25, 8], ['rotate', Math.PI / 2]])
    expect(fills.at(-1)?.x).toBe(260)
  })

  it('space the word glyph by glyph, half the space before each, as letterSpacing does', async () => {
    // tracking .25 at size 20 is 5 per glyph: 40 + 4 * 5 = 60 wide, 140 free on the right.
    const { fills } = paint(await sceneWith({ text: 'ABCD', tracking: 0.25, align: 'right' }))
    expect(fills.slice(-4).map((fill) => [fill.text, fill.x])).toEqual([['A', 142.5], ['B', 157.5], ['C', 172.5], ['D', 187.5]])
  })
})
