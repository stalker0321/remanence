/**
 * Letter on the back of the card, connected viewer proof (Chromium-only).
 *
 * Mounts the ACTUAL exported `CollageStage` with the real bundled fonts, the
 * real `paintScene` and synthetic JPEG photos. It proves that a sheet with a
 * letter gets the turn mark inside its note, that the mark covers no painted
 * word, that the note turns the card to the whole text and "the front" turns
 * it back, that only the showing side takes input, that another sheet opens
 * on its front, and that "save picture" still saves the front.
 */
import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { CollageStage } from '../../src/collage/stage'
import type { HeldSheet } from '../../src/collage/memory'
import { letterMarkPlace } from '../../src/collage/CardBack'
import { layoutCreatorNote } from '../../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { canvasTextMeasurer } from '../../src/collage/measure'
import { styleById } from '../../src/collage/styles'
import { splitLetter } from '../../src/create/letter'
import { catalog, type MessageKey } from '../../src/i18n/catalog'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const t = (key: MessageKey): string => catalog.en[key]
const STYLE = 'press'
const LETTER = [
  'Mum, we made it to the sea at last. The water is cold and the kids will not leave it.',
  'We took the long road through the hills, the one you always wanted to see. There is a small white church at the top and a bench beside it; we sat there for an hour and nobody said a word.',
  'I will tell you the rest when we are home. Keep the card on the fridge until then.',
].join('\n\n')
const SHORT = 'Back on Sunday.'
const GREEN = '#2e8b57'
const RED = '#b33b2e'
const BLUE = '#245a9c'

const downloads: Array<{ href: string; download: string }> = []
const exportBlobs: Blob[] = []
const originalCreate = URL.createObjectURL.bind(URL)
URL.createObjectURL = (blob: Blob): string => {
  if (blob.type !== 'image/png') return originalCreate(blob)
  exportBlobs.push(blob)
  return `blob:stage-letter/${exportBlobs.length}`
}
const originalRevoke = URL.revokeObjectURL.bind(URL)
URL.revokeObjectURL = (url: string): void => { if (!url.startsWith('blob:stage-letter/')) originalRevoke(url) }
HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement): void {
  downloads.push({ href: this.href, download: this.download })
}

function tintedPhoto(index: number, tint: string): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = 800
  canvas.height = 800
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = tint
  context.fillRect(0, 0, 800, 800)
  context.fillStyle = index % 2 === 0 ? '#ffffff' : '#222222'
  context.fillRect(100 + index * 40, 120, 260, 420)
  return canvas
}

function canvasJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('no jpeg')), 'image/jpeg', 0.9)
  })
}

/** A sheet the way a receiver holds it: the sealed front note with its rows, and the letter beside it. */
async function buildHeld(tint: string, text: string): Promise<HeldSheet> {
  const split = splitLetter(text)
  const canvases = [0, 1, 2].map((index) => tintedPhoto(index, tint))
  const urls = await Promise.all(canvases.map(async (canvas) => originalCreate(await canvasJpeg(canvas))))
  const photos = canvases.map((canvas, index) => ({
    contentId: `p${index}`,
    width: canvas.width,
    height: canvas.height,
    contentHash: `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32),
  }))
  const style = styleById(STYLE)!
  const measurer = canvasTextMeasurer(document)
  const view = previewCandidates({ id: 'creator', sender: 'sergio', photos, note: split.front }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view,
    styleId: STYLE,
    senderHandle: 'sergio',
    noteText: split.front,
    photos: new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  const note = scene.note
  if (note === null) throw new Error('the planned sheet has no note')
  const face = { font: note.family, weight: note.weight, italic: note.italic, size: note.size, line: note.line }
  const lineWidth = (line: string): number =>
    measurer.measure({
      text: line, family: note.family, size: note.size, weight: note.weight, italic: note.italic, line: note.line,
      maxWidth: Number.POSITIVE_INFINITY, maxHeight: Number.POSITIVE_INFINITY, softWrap: false,
    }).width
  const noteLayout = layoutCreatorNote(split.front, face, note.region, lineWidth)
  if (noteLayout.overflow) throw new Error('the front note does not fit its region')
  return {
    scene: { ...scene, note: { ...note, noteLayout } },
    photos: new Map(photos.map((photo, index) => [photo.contentId, urls[index]!])),
    artwork: null,
    letter: split.letter,
    voice: null,
    sound: null,
    video: null,
    film: null,
  }
}

async function waitFor(condition: () => boolean, what: string, timeoutMs = 30000): Promise<void> {
  const deadline = performance.now() + timeoutMs
  while (performance.now() < deadline) {
    try {
      if (condition()) return
    } catch {
      // The queried control may be transiently absent during a React commit.
    }
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  throw new Error(`timeout: ${what}`)
}

const sides = (): HTMLElement => document.querySelector('.stage .card-sides') as HTMLElement
const front = (): HTMLElement => document.querySelector('.stage .sheet-front') as HTMLElement
const back = (): HTMLElement | null => document.querySelector('.stage .sheet-back')
const mark = (): HTMLButtonElement | null => document.querySelector('.stage button.letter-mark')
const stageCanvas = (): HTMLCanvasElement => document.querySelector('.stage canvas') as HTMLCanvasElement
const exportButton = (): HTMLButtonElement =>
  [...document.querySelectorAll('button')].find((button) => button.getAttribute('aria-label') === t('exportComposition')) as HTMLButtonElement

/**
 * The stage shows this sheet's own painting: the export gate is open and the
 * first photo's corner carries the sheet's tint. The gate alone can still be
 * the previous sheet's while the next one decodes.
 */
function painted(held: HeldSheet, tint: string): boolean {
  if (exportButton().disabled) return false
  const corner = held.scene.photos[0]!.destination
  const pixel = stageCanvas().getContext('2d')!.getImageData(Math.ceil(corner.x) + 3, Math.ceil(corner.y) + 3, 1, 1).data
  const want = [1, 3, 5].map((at) => parseInt(tint.slice(at, at + 2), 16))
  return pixel[3] === 255 && want.every((value, index) => Math.abs(pixel[index]! - value) <= 24)
}

function intersects(a: DOMRect, b: DOMRect): boolean {
  return a.left < b.right - 0.5 && b.left < a.right - 0.5 && a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5
}

/** Which side a pointer in the middle of the card would reach. */
function sideAtCentre(): 'front' | 'back' | 'other' {
  const box = sides().getBoundingClientRect()
  const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
  if (hit === null) return 'other'
  if (back()?.contains(hit)) return 'back'
  return front().contains(hit) ? 'front' : 'other'
}

/** The page waits here so the runner can photograph the named side; alone it just goes on. */
async function pose(name: string, statusNode: HTMLElement): Promise<void> {
  const flags = window as unknown as { __stageLetterShot?: string }
  statusNode.textContent = `pose:${name}`
  const deadline = performance.now() + 4000
  while (flags.__stageLetterShot !== name && performance.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  statusNode.textContent = 'running'
}

async function main(): Promise<void> {
  const statusNode = document.createElement('p')
  statusNode.id = 'stage-letter-status'
  statusNode.textContent = 'starting'
  document.body.appendChild(statusNode)
  const report: Record<string, unknown> = { pass: false, synthetic: true }
  let step = 'fonts'
  try {
    const style = styleById(STYLE)!
    await document.fonts.load(`${style.note.italic ? 'italic ' : ''}${style.note.weight} 20px ${style.note.font}`)

    step = 'build-helds'
    const plain = await buildHeld(GREEN, SHORT)
    const letter = await buildHeld(RED, LETTER)
    const other = await buildHeld(BLUE, `${LETTER}\n\nP.S. Water the plants.`)
    assert('a short text has no letter', plain.letter === null)
    assert('a long text is sealed whole as the letter', letter.letter === LETTER)
    assert('the front is the start of the letter', LETTER.startsWith(letter.scene.note!.text) && letter.scene.note!.text.length < LETTER.length, letter.scene.note!.text)

    const root: { current: Root | null } = { current: null }
    const host = document.createElement('div')
    document.body.appendChild(host)
    const render = (held: HeldSheet): void => {
      root.current ??= createRoot(host)
      flushSync(() => {
        root.current!.render(<CollageStage held={held} t={t} onClose={() => undefined} />)
      })
    }

    step = 'no-letter'
    render(plain)
    await waitFor(() => painted(plain, GREEN), 'plain sheet painted')
    assert('no letter: no turn mark', mark() === null)
    assert('no letter: no back', back() === null)

    step = 'mark'
    render(letter)
    await waitFor(() => painted(letter, RED), 'letter sheet painted')
    const button = mark()
    assert('letter: the turn mark is there', button !== null)
    if (button === null) throw new Error('no mark')
    assert('mark: named for assistive tech', button.getAttribute('aria-label') === t('letterMark') && button.title === t('letterMark'), button.getAttribute('aria-label') ?? '')
    assert('letter: starts on the front', sides().dataset.turned === undefined && !front().inert && back()!.inert)
    assert('front showing: the pointer reaches the front', sideAtCentre() === 'front', sideAtCentre())

    const scene = letter.scene
    const note = scene.note!
    const sheet = stageCanvas().getBoundingClientRect()
    const scale = sheet.width / scene.canvasWidth
    const hit = button.getBoundingClientRect()
    const want = { left: sheet.left + note.region.x * scale, top: sheet.top + note.region.y * scale, width: note.region.width * scale, height: note.region.height * scale }
    assert(
      'mark: the whole note is the button',
      Math.abs(hit.left - want.left) < 1 && Math.abs(hit.top - want.top) < 1 && Math.abs(hit.width - want.width) < 1 && Math.abs(hit.height - want.height) < 1,
      JSON.stringify({ hit: [hit.left, hit.top, hit.width, hit.height], want }),
    )
    const place = letterMarkPlace(note)
    const arrow = button.querySelector('svg')!.getBoundingClientRect()
    assert(
      'mark: the arrow is drawn where the rule puts it',
      Math.abs(arrow.left - (sheet.left + place.x * scale)) < 1 && Math.abs(arrow.top - (sheet.top + place.y * scale)) < 1 && Math.abs(arrow.width - place.size * scale) < 1,
      JSON.stringify({ arrow: [arrow.left, arrow.top, arrow.width], place, scale }),
    )
    assert('mark: the arrow stays inside the note', arrow.left >= hit.left - 0.5 && arrow.right <= hit.right + 0.5 && arrow.top >= hit.top - 0.5 && arrow.bottom <= hit.bottom + 0.5)
    const photoHits = [...document.querySelectorAll('.stage .sheet-front button.hit')].map((item) => item.getBoundingClientRect())
    assert('mark: the note button covers no photo', photoHits.length === 3 && photoHits.every((rect) => !intersects(rect, hit)))
    assert('mark: 12 to 18 px on the sheet', place.size >= 12 && place.size <= 18, String(place.size))

    // Nothing the canvas painted lies under the arrow: every pixel there is the paper.
    const rows = note.noteLayout!.lines.filter((row) => row.text !== '')
    const last = rows[rows.length - 1]!
    report.mark = { ...place, lastLine: last.text, lastLineWidth: last.width, noteSize: note.size, region: note.region, rows: rows.length }
    if (!place.backed) {
      const context = stageCanvas().getContext('2d')!
      const x0 = Math.ceil(place.x)
      const y0 = Math.ceil(place.y)
      const side = Math.floor(place.size) - 1
      const pixels = context.getImageData(x0, y0, side, side).data
      const paper = [(scene.paper >>> 16) & 255, (scene.paper >>> 8) & 255, scene.paper & 255]
      let inked = 0
      for (let index = 0; index < pixels.length; index += 4) {
        if (Math.abs(pixels[index]! - paper[0]!) > 6 || Math.abs(pixels[index + 1]! - paper[1]!) > 6 || Math.abs(pixels[index + 2]! - paper[2]!) > 6) inked++
      }
      assert('mark: no painted word under the arrow', inked === 0, `${inked} inked pixels of ${pixels.length / 4}; first ${[...pixels.slice(0, 4)]} against paper ${paper}`)
      assert('mark: right after the last word', Math.abs(place.x - (note.region.x + last.width)) <= Math.max(4, Math.round(note.size * 0.3)) + 1e-6)
    } else {
      assert('mark: a full last line ends in the arrow', Math.abs(place.x + place.size - (note.region.x + note.region.width)) < 1e-6)
    }
    await pose('front', statusNode)

    step = 'turn'
    button.click()
    await waitFor(() => sides().dataset.turned === 'true', 'card turned')
    assert('turned: the front takes no input', front().inert)
    assert('turned: the back takes input', back() !== null && !back()!.inert)
    assert('turned: the back holds the whole text', document.querySelector('.stage .card-back-text')?.textContent === LETTER)
    assert('turned: the back is named', document.querySelector('.stage .card-back')?.getAttribute('aria-label') === t('letterTitle'))
    const backStyle = getComputedStyle(document.querySelector('.stage .card-back') as HTMLElement)
    assert('turned: the back is in the note face', backStyle.fontFamily.includes(note.family), backStyle.fontFamily)
    await new Promise((resolve) => setTimeout(resolve, 750))
    assert('turned: the pointer reaches the back', sideAtCentre() === 'back', sideAtCentre())
    const backBox = back()!.getBoundingClientRect()
    assert('turned: the back lies on the sheet', Math.abs(backBox.left - sheet.left) < 1.5 && Math.abs(backBox.width - sheet.width) < 1.5 && Math.abs(backBox.height - sheet.height) < 1.5, JSON.stringify([backBox.left, backBox.width, backBox.height]))
    const text = document.querySelector('.stage .card-back-text') as HTMLElement
    assert('turned: the text keeps to the card width', text.scrollWidth <= text.clientWidth + 1, `${text.scrollWidth} > ${text.clientWidth}`)
    await pose('back', statusNode)

    step = 'export-turned'
    // "Save picture" still saves the front, also from the back.
    assert('turned: save picture is offered', !exportButton().disabled)
    const before = downloads.length
    exportButton().click()
    await waitFor(() => downloads.length === before + 1, 'export from the back')
    const png = new Uint8Array(await exportBlobs[exportBlobs.length - 1]!.arrayBuffer())
    const view = new DataView(png.buffer)
    assert('turned: the saved picture is the 360x640 front', view.getUint32(16) === scene.canvasWidth && view.getUint32(20) === scene.canvasHeight, `${view.getUint32(16)}x${view.getUint32(20)}`)

    step = 'turn-back'
    const turnBack = document.querySelector('.stage button.card-back-turn') as HTMLButtonElement
    assert('turned: "the front" is offered', turnBack !== null && (turnBack.textContent ?? '').includes(t('letterFront')))
    turnBack.click()
    await waitFor(() => sides().dataset.turned === undefined, 'card on its front again')
    assert('front again: the front takes input', !front().inert && back()!.inert)
    assert('front again: the mark is back', mark() !== null)
    await new Promise((resolve) => setTimeout(resolve, 750))
    assert('front again: the pointer reaches the front', sideAtCentre() === 'front', sideAtCentre())

    step = 'another-sheet'
    // A card left on its back does not turn the next one over.
    mark()!.click()
    await waitFor(() => sides().dataset.turned === 'true', 'card turned a second time')
    render(other)
    await waitFor(() => painted(other, BLUE), 'other sheet painted')
    assert('another sheet opens on its front', sides().dataset.turned === undefined && !front().inert)
    assert('another sheet has its own letter behind it', document.querySelector('.stage .card-back-text')?.textContent === other.letter)
    render(plain)
    await waitFor(() => painted(plain, GREEN), 'plain sheet painted again')
    assert('a sheet without a letter drops the mark and the back', mark() === null && back() === null)

    report.downloads = downloads.length
    report.pass = failures.length === 0
  } catch (error) {
    fail('stage letter connected harness', String(error) + ' | step=' + step)
  }
  ;(window as unknown as { __stageLetter?: unknown }).__stageLetter = { assertions, failures, report }
  statusNode.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
