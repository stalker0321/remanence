/**
 * Video square on the card, connected viewer proof (Chromium-only).
 *
 * Mounts the ACTUAL exported `CollageStage` with the real bundled fonts, the
 * real `paintScene`, synthetic JPEG photos, a real two-second 480×480 H.264 +
 * AAC film and a real two-second AAC voice note. The layout is one the sender
 * is offered for a card with a video: the first frame sits in a square tile.
 * It proves that the square covers exactly that tile, loops silently while
 * the card is open, plays from the start with sound when pressed, goes quiet
 * when the voice note starts (and stops the voice note when pressed again),
 * and returns to the silent loop at its end.
 */
import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { CollageStage } from '../../src/collage/stage'
import type { HeldSheet } from '../../src/collage/memory'
import { creatorChoices, creatorScene } from '../../src/collage/creatorPreview'
import { canvasTextMeasurer } from '../../src/collage/measure'
import { VOICE_PEAKS } from '../../src/collage/songBand'
import { catalog, type MessageKey } from '../../src/i18n/catalog'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const t = (key: MessageKey): string => catalog.en[key]
const TINTS = ['#2e8b57', '#b33b2e', '#245a9c', '#8a5a2b']
const PEAKS = Uint8Array.from({ length: VOICE_PEAKS }, (_, index) => Math.round(127 + 120 * Math.sin(index / 3)))
const POSTER = 'p3'

function tintedPhoto(index: number, tint: string, width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = tint
  context.fillRect(0, 0, width, height)
  context.fillStyle = index % 2 === 0 ? '#ffffff' : '#222222'
  context.fillRect(width * 0.15, height * 0.2, width * 0.35, height * 0.5)
  return canvas
}

function canvasJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('no jpeg')), 'image/jpeg', 0.9)
  })
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

const square = (): HTMLButtonElement | null => document.querySelector('.stage button.video-square')
const film = (): HTMLVideoElement | null => document.querySelector('.stage button.video-square video')
const band = (): HTMLButtonElement | null => document.querySelector('.stage button.voice-band')
const voice = (): HTMLAudioElement | null => document.querySelector('.stage button.voice-band audio')
const stageCanvas = (): HTMLCanvasElement => document.querySelector('.stage canvas') as HTMLCanvasElement

async function advancing(element: () => HTMLMediaElement | null, what: string): Promise<void> {
  const start = element()?.currentTime ?? 0
  await waitFor(() => (element()?.currentTime ?? 0) > start + 0.15 || ((element()?.currentTime ?? 0) > 0.15 && (element()?.currentTime ?? 0) < start), what, 5000)
}

async function pose(name: string, statusNode: HTMLElement): Promise<void> {
  const flags = window as unknown as { __stageVideoShot?: string }
  statusNode.textContent = `pose:${name}`
  const deadline = performance.now() + 4000
  while (flags.__stageVideoShot !== name && performance.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  statusNode.textContent = 'running'
}

async function main(): Promise<void> {
  const statusNode = document.createElement('p')
  statusNode.id = 'stage-video-status'
  statusNode.textContent = 'starting'
  document.body.appendChild(statusNode)
  const report: Record<string, unknown> = { pass: false, synthetic: true }
  let step = 'fonts'
  try {
    await document.fonts.load('400 20px StudySans')
    await document.fonts.load('700 20px StudySans')

    step = 'media'
    const filmUrl = URL.createObjectURL(new Blob([await (await fetch('./video-2s.mp4')).arrayBuffer()], { type: 'video/mp4' }))
    const soundUrl = URL.createObjectURL(new Blob([await (await fetch('./voice-2s.m4a')).arrayBuffer()], { type: 'audio/mp4' }))
    report.canPlayH264 = document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01E, mp4a.40.2"')

    step = 'layout'
    const sizes: Array<[number, number]> = [[1200, 800], [800, 1200], [1200, 900], [480, 480]]
    const canvases = sizes.map(([width, height], index) => tintedPhoto(index, TINTS[index]!, width, height))
    const urls = await Promise.all(canvases.map(async (canvas) => URL.createObjectURL(await canvasJpeg(canvas))))
    const photos = canvases.map((canvas, index) => ({
      contentId: `p${index}`,
      width: canvas.width,
      height: canvas.height,
      contentHash: `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32),
    }))
    const measurer = canvasTextMeasurer(document)
    const voiceWords = { peaks: PEAKS, durationMs: 2000 }
    const views = creatorChoices({
      preview: { id: 'creator', sender: 'sergio', photos, note: 'Back on Sunday.', music: { artwork: false } },
      measurer,
      track: null,
      voice: voiceWords,
      seed: 'stage-video',
      posterContentId: POSTER,
    })
    report.layouts = views.map((view) => `${view.grammarId}/${view.styleId}`)
    assert('layouts are offered for a card with a video and a voice note', views.length >= 3, String(views.length))
    const view = views[0]!
    const scene = creatorScene({
      view,
      styleId: view.styleId,
      senderHandle: 'sergio',
      noteText: 'Back on Sunday.',
      photos: new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
      voice: voiceWords,
      measurer,
    })
    const held: HeldSheet = {
      scene,
      photos: new Map(photos.map((photo, index) => [photo.contentId, urls[index]!])),
      artwork: null,
      letter: null,
      voice: { blobId: new Uint8Array(16), mimeType: 'audio/mp4', durationMs: 2000, peaks: PEAKS },
      sound: soundUrl,
      video: { blobId: new Uint8Array(16).fill(1), mimeType: 'video/mp4', durationMs: 2000, size: 480, posterContentId: POSTER, posterSide: 480 },
      film: filmUrl,
    }

    const root: { current: Root | null } = { current: null }
    const host = document.createElement('div')
    document.body.appendChild(host)
    const render = (sheet: HeldSheet | null): void => {
      root.current ??= createRoot(host)
      flushSync(() => {
        root.current!.render(sheet === null ? null : <CollageStage held={sheet} t={t} onClose={() => undefined} />)
      })
    }

    step = 'mount'
    render(held)
    await waitFor(() => square() !== null && film() !== null, 'video square mounted')
    const tile = scene.photos.find((photo) => photo.contentId === POSTER)!.destination
    const box = stageCanvas().getBoundingClientRect()
    const scale = box.width / scene.canvasWidth
    const hit = square()!.getBoundingClientRect()
    assert('the square covers exactly its first frame\'s tile',
      Math.abs(hit.left - (box.left + tile.x * scale)) < 1 && Math.abs(hit.top - (box.top + tile.y * scale)) < 1
        && Math.abs(hit.width - tile.width * scale) < 1 && Math.abs(hit.height - tile.height * scale) < 1,
      JSON.stringify({ hit: [hit.left, hit.top, hit.width, hit.height], tile }))
    assert('the tile is square and at least 140 units', Math.abs(tile.width - tile.height) <= 0.05 * tile.width && Math.min(tile.width, tile.height) >= 140, JSON.stringify(tile))
    assert('named "watch with sound"', square()!.getAttribute('aria-label') === t('videoWatch') && square()!.getAttribute('aria-pressed') === 'false')
    assert('shows the length', square()!.querySelector('.video-square-length')?.textContent === '0:02')

    step = 'silent-loop'
    await advancing(film, 'the silent loop plays')
    assert('it loops silently while the card is open', film()!.muted && film()!.loop && !film()!.paused)
    await pose('silent', statusNode)

    step = 'sound'
    square()!.click()
    await waitFor(() => square()!.getAttribute('aria-pressed') === 'true', 'sound on')
    await advancing(film, 'the film plays with sound')
    assert('pressing plays with sound', !film()!.muted && !film()!.paused && square()!.getAttribute('aria-label') === t('videoMute'))
    assert('playing with sound shows a progress line', square()!.querySelector('.video-square-progress') !== null)
    await pose('sound', statusNode)

    step = 'voice-takes-over'
    band()!.click()
    await waitFor(() => band()!.getAttribute('aria-pressed') === 'true', 'voice playing')
    await waitFor(() => square()!.getAttribute('aria-pressed') === 'false', 'video quiet')
    assert('the voice note silences the video, which keeps looping', film()!.muted && !film()!.paused)

    step = 'video-takes-over'
    square()!.click()
    await waitFor(() => square()!.getAttribute('aria-pressed') === 'true', 'sound on again')
    await waitFor(() => voice()!.paused, 'voice paused')
    assert('the video\'s sound pauses the voice note', voice()!.paused && band()!.getAttribute('aria-pressed') === 'false')

    step = 'end'
    await waitFor(() => square()!.getAttribute('aria-pressed') === 'false', 'the end of the film', 6000)
    await advancing(film, 'back to the silent loop')
    assert('at its end it goes back to the silent loop', film()!.muted && !film()!.paused)

    step = 'close'
    const element = film()!
    render(null)
    assert('closing the card stops the film', element.paused)

    report.pass = failures.length === 0
  } catch (error) {
    fail('stage video connected harness', String(error) + ' | step=' + step)
  }
  ;(window as unknown as { __stageVideo?: unknown }).__stageVideo = { assertions, failures, report }
  statusNode.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
