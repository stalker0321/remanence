/**
 * T243 receiver frozen-note browser fixture (Chromium-only gate).
 *
 * Runs the REAL production `prepare` -> `paintScene` on VALID frozen V3
 * expression fixtures (`sheets.pressSheet`, the committed Android codec
 * golden shape) with multi-line notes and traces actual `fillText` calls.
 * There is no plan regeneration and no admission bypass: the note region/type
 * come from the frozen expression payload. Sources are synthetic canvases (no
 * camera/provider), reported via `window.__receiverNote`.
 */
import { canvasTextMeasurer } from '../../src/collage/measure'
import type { ResolvedExpression } from '../../src/collage/model'
import { paintScene, type PaintImage } from '../../src/collage/paint'
import { prepare, type Scene } from '../../src/collage/prepare'
import { pressSheet } from '../sheets'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

const LINE = 18
const SIZE = 16
const RX = 16
const RY = 120

const EXPLICIT = 'first line\nsecond line\n\nfourth line четвертий'
const WRAPPED = 'слово слово слово слово слово слово слово слово слово слово слово слово'
const OVERWIDE = 'supercalifragilisticexpialidocioussupercalifragilisticexpialidocious'
const SHORT = 'Probe note text.'

function twoTone(width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#c0392b'
  context.fillRect(0, 0, width / 2, height)
  context.fillStyle = '#2471a3'
  context.fillRect(width / 2, 0, width / 2, height)
  return canvas
}

async function fixture(note: string | null, width: number, height: number): Promise<ResolvedExpression> {
  const base = await pressSheet('press')
  return {
    ...base,
    noteRegion: note === null ? null : { x: RX, y: RY, width, height },
    musicRegion: null,
    input: { ...base.input, note, music: null },
  }
}

async function ensureFaces(): Promise<void> {
  await Promise.all([
    document.fonts.load('normal 400 16px StudySans'),
    document.fonts.load('normal 400 64px StudySerif'),
    document.fonts.load('italic 400 20px StudySerif'),
  ])
}

function installFillTextTrace(): Array<{ text: string; x: number; y: number; font: string }> {
  const calls: Array<{ text: string; x: number; y: number; font: string }> = []
  const proto = CanvasRenderingContext2D.prototype as unknown as {
    fillText: (this: CanvasRenderingContext2D, text: string, x: number, y: number) => void
  }
  const real = proto.fillText
  proto.fillText = function (this: CanvasRenderingContext2D, text: string, x: number, y: number): void {
    calls.push({ text, x, y, font: this.font })
    real.call(this, text, x, y)
  }
  return calls
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'receiver-note-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const canvas = document.createElement('canvas')
  canvas.width = 360
  canvas.height = 640
  canvas.id = 'receiver-note-sheet'
  document.body.appendChild(canvas)
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const images = new Map<string, PaintImage>()
  for (const id of ['p1', 'p2', 'p3']) images.set(id, { source: twoTone(120, 213), width: 120, height: 213 })
  try {
    await ensureFaces()
    const measurer = canvasTextMeasurer(document)
    const sources = new Map<string, { width: number; height: number }>()
    for (const id of ['p1', 'p2', 'p3']) sources.set(id, { width: 120, height: 213 })

    const traced = installFillTextTrace()
    const noteRows = (): Array<{ text: string; x: number; y: number; font: string }> =>
      traced.filter((call) => call.font.includes('StudySans'))

    // Frozen explicit-break note: rows are the input's non-empty lines in order.
    traced.length = 0
    const explicit = await fixture(EXPLICIT, 328, 120)
    const explicitReady = await prepare(explicit, EXPLICIT, null, sources, measurer)
    assert('explicit frozen note prepares ready', explicitReady.kind === 'ready', JSON.stringify(explicitReady))
    if (explicitReady.kind === 'ready') {
      assert('explicit frozen note paints', paintScene(context, explicitReady.scene, images, null))
      const rows = noteRows()
      assert(
        'explicit frozen note painted its non-empty lines in order',
        JSON.stringify(rows.map((row) => row.text)) === JSON.stringify(['first line', 'second line', 'fourth line четвертий']),
        JSON.stringify(rows.map((row) => row.text)),
      )
      assert(
        'explicit empty line advanced a full row',
        rows.length === 3 && Math.abs(rows[1]!.y - rows[0]!.y - LINE) < 1e-6 && Math.abs(rows[2]!.y - rows[1]!.y - 2 * LINE) < 1e-6,
        JSON.stringify(rows.map((row) => row.y)),
      )
      // Compare against the browser's OWN serialization of the intended
      // computed face (family/weight/size/italic), not a magic string: the
      // assertion still proves the painter set the frozen note's real face.
      const fontProbe = document.createElement('canvas').getContext('2d')
      const expectedFont = (() => {
        if (!fontProbe) return ''
        fontProbe.font = `normal 400 ${SIZE}px StudySans`
        return fontProbe.font
      })()
      assert(
        'explicit rows use the frozen note face/type',
        expectedFont !== '' && rows.length === 3 && rows.every((row) => row.font === expectedFont),
        `expected="${expectedFont}" got=${JSON.stringify(rows.map((row) => row.font))}`,
      )
      assert('explicit rows stay inside the frozen region', rows.every((row) => row.x === RX && row.y >= RY && row.y <= RY + 120))
    }

    // Frozen RU wrapped note: every input word is painted across multiple rows.
    traced.length = 0
    const wrapped = await fixture(WRAPPED, 140, 200)
    const wrappedReady = await prepare(wrapped, WRAPPED, null, sources, measurer)
    assert('wrapped RU frozen note prepares ready', wrappedReady.kind === 'ready', JSON.stringify(wrappedReady))
    if (wrappedReady.kind === 'ready') {
      assert('wrapped RU frozen note paints', paintScene(context, wrappedReady.scene, images, null))
      const painted = noteRows().map((row) => row.text).join(' ').split(/\s+/).filter((word) => word.length > 0)
      const input = WRAPPED.split(/\s+/).filter((word) => word.length > 0)
      assert('every wrapped RU word is painted', JSON.stringify(painted) === JSON.stringify(input), `${painted.length}/${input.length}`)
      assert('wrapped RU note uses multiple rows', noteRows().length > 1, String(noteRows().length))
    }

    // Short frozen note stays a single row.
    traced.length = 0
    const short = await fixture(SHORT, 328, 90)
    const shortReady = await prepare(short, SHORT, null, sources, measurer)
    assert('short frozen note prepares ready', shortReady.kind === 'ready', JSON.stringify(shortReady))
    if (shortReady.kind === 'ready') {
      paintScene(context, shortReady.scene, images, null)
      assert('short frozen note paints exactly one row', noteRows().length === 1 && noteRows()[0]?.text === SHORT, JSON.stringify(noteRows()))
    }

    // Over-wide unbreakable word refuses (never clipped-ready).
    const overwide = await prepare(await fixture(OVERWIDE, 140, 200), OVERWIDE, null, sources, measurer)
    assert('over-wide unbreakable frozen note refuses', overwide.kind === 'rejected', JSON.stringify(overwide))

    // Height overflow refuses.
    const tall = await prepare(await fixture(EXPLICIT, 328, LINE), EXPLICIT, null, sources, measurer)
    assert('height-overflow frozen note refuses', tall.kind === 'rejected', JSON.stringify(tall))

    // No-note case is unchanged.
    traced.length = 0
    const noNote = await prepare(await fixture(null, 0, 0), null, null, sources, measurer)
    assert('no-note frozen expression stays ready without a note scene', noNote.kind === 'ready' && (noNote as { scene: Scene }).scene.note === null)
    assert('no-note paints no note rows', noteRows().length === 0)

    // Re-paint the explicit sheet last for a useful screenshot.
    if (explicitReady.kind === 'ready') paintScene(context, explicitReady.scene, images, null)
  } catch (error) {
    assert('receiver note harness', false, String(error))
  }
  ;(window as unknown as { __receiverNote?: unknown }).__receiverNote = { assertions, failures }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
