/**
 * Voice note in the song band, connected viewer proof (Chromium-only).
 *
 * Mounts the ACTUAL exported `CollageStage` with the real bundled fonts, the
 * real `paintScene`, synthetic JPEG photos and a real two-second AAC sound.
 * The voice band is laid out by the shared `voiceBandLayout` in place of a
 * planned song band, the way `prepare` draws a received voice note. It proves
 * that the whole band is the button, that it plays and pauses the sound with
 * a pause glyph over the painted play button, that the end of the sound
 * resets it, and that another sheet stops it.
 */
import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { CollageStage } from '../../src/collage/stage'
import type { HeldSheet } from '../../src/collage/memory'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { canvasTextMeasurer } from '../../src/collage/measure'
import { runWidth, type Scene } from '../../src/collage/prepare'
import { voiceBandLayout, VOICE_PEAKS, type SongBandMode } from '../../src/collage/songBand'
import { styleById } from '../../src/collage/styles'
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
const TINTS = ['#2e8b57', '#b33b2e', '#245a9c', '#8a5a2b']
const PEAKS = Uint8Array.from({ length: VOICE_PEAKS }, (_, index) => Math.round(127 + 120 * Math.sin(index / 3)))

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

/**
 * Planned song sheets (two grammars, two titles, 3 to 5 photos, with and
 * without a note), each band swapped for a voice note of `durationMs`:
 * between them, every form.
 */
async function voiceSheets(tint: string, sound: string | null, durationMs: number): Promise<HeldSheet[]> {
  const canvases = [0, 1, 2, 3, 4].map((index) => tintedPhoto(index, tint))
  const urls = await Promise.all(canvases.map(async (canvas) => URL.createObjectURL(await canvasJpeg(canvas))))
  const all = canvases.map((canvas, index) => ({
    contentId: `p${index}`,
    width: canvas.width,
    height: canvas.height,
    contentHash: `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32),
  }))
  const measurer = canvasTextMeasurer(document)
  const held: HeldSheet[] = []
  // A short title keeps the caption; a long one the plate; grammar 6 lays the band along its spine.
  for (const choice of [{ grammarId: 5, styleId: STYLE }, { grammarId: 6, styleId: 'signal' }]) {
    const style = styleById(choice.styleId)!
    for (const title of ['Probe song', 'A long song title that takes more than one line of the band']) {
      for (const count of [3, 4, 5]) {
        for (const note of ['Back on Sunday.', null]) {
          const photos = all.slice(0, count)
          let views: ReturnType<typeof previewCandidates>
          try {
            views = previewCandidates(
              { id: 'creator', sender: 'sergio', photos, note, music: { artwork: false } },
              undefined,
              plannerFromTextMeasurer(measurer, 640, 640, style.note),
              choice,
            )
          } catch {
            continue
          }
          for (const view of views) {
            const scene = creatorScene({
              view,
              styleId: choice.styleId,
              senderHandle: 'sergio',
              noteText: note,
              photos: new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
              track: { title, artistDisplay: 'Probe singer' },
              measurer,
            })
            const music = scene.music
            const mode = music?.band?.mode
            if (!music || (mode !== 'plate' && mode !== 'caption' && mode !== 'card')) continue
            const face = { family: music.family, size: music.size, weight: music.weight, italic: music.italic, line: music.line }
            const band = voiceBandLayout(music.region, mode as SongBandMode, face, { peaks: PEAKS, durationMs }, runWidth(measurer))
            const voiced: Scene = { ...scene, music: { ...music, title: '', artist: '', text: '', playRegion: band.play, band } }
            held.push({
              scene: voiced,
              photos: new Map(photos.map((photo, index) => [photo.contentId, urls[index]!])),
              artwork: null,
              letter: null,
              voice: { blobId: new Uint8Array(16), mimeType: 'audio/mp4', durationMs, peaks: PEAKS },
              sound,
              video: null,
              film: null,
            })
          }
        }
      }
    }
  }
  return held
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

const band = (): HTMLButtonElement | null => document.querySelector('.stage button.voice-band')
const audio = (): HTMLAudioElement | null => document.querySelector('.stage button.voice-band audio')
const glyph = (): SVGSVGElement | null => document.querySelector('.stage button.voice-band svg')
const stageCanvas = (): HTMLCanvasElement => document.querySelector('.stage canvas') as HTMLCanvasElement
const exportButton = (): HTMLButtonElement =>
  [...document.querySelectorAll('button')].find((button) => button.getAttribute('aria-label') === t('exportComposition')) as HTMLButtonElement

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

function near(rect: DOMRect, want: { left: number; top: number; width: number; height: number }): boolean {
  return Math.abs(rect.left - want.left) < 1 && Math.abs(rect.top - want.top) < 1 && Math.abs(rect.width - want.width) < 1 && Math.abs(rect.height - want.height) < 1
}

/** The page waits here so the runner can photograph the pose; alone it just goes on. */
async function pose(name: string, statusNode: HTMLElement): Promise<void> {
  const flags = window as unknown as { __stageVoiceShot?: string }
  statusNode.textContent = `pose:${name}`
  const deadline = performance.now() + 4000
  while (flags.__stageVoiceShot !== name && performance.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  statusNode.textContent = 'running'
}

async function main(): Promise<void> {
  const statusNode = document.createElement('p')
  statusNode.id = 'stage-voice-status'
  statusNode.textContent = 'starting'
  document.body.appendChild(statusNode)
  const report: Record<string, unknown> = { pass: false, synthetic: true }
  let step = 'fonts'
  try {
    const style = styleById(STYLE)!
    await document.fonts.load(`${style.note.italic ? 'italic ' : ''}${style.note.weight} 20px ${style.note.font}`)
    await document.fonts.load('400 12px StudySans')

    step = 'sound'
    const bytes = new Uint8Array(await (await fetch('./voice-2s.m4a')).arrayBuffer())
    const sound = URL.createObjectURL(new Blob([bytes], { type: 'audio/mp4' }))
    report.canPlayAac = document.createElement('audio').canPlayType('audio/mp4; codecs="mp4a.40.2"')

    step = 'build-helds'
    const silent = (await voiceSheets(TINTS[3]!, null, 2000))[0]!
    const sheets = await voiceSheets(TINTS[0]!, sound, 2000)
    const modes = new Map<string, HeldSheet>()
    for (const held of sheets) if (!modes.has(held.scene.music!.band!.mode)) modes.set(held.scene.music!.band!.mode, held)
    report.modes = [...modes.keys()]
    report.grammars = [...modes.values()].map((held) => held.scene.grammarId)
    assert('the planner offers all three forms', ['plate', 'caption', 'card'].every((mode) => modes.has(mode)), [...modes.keys()].join())

    const root: { current: Root | null } = { current: null }
    const host = document.createElement('div')
    document.body.appendChild(host)
    const render = (held: HeldSheet): void => {
      root.current ??= createRoot(host)
      flushSync(() => {
        root.current!.render(<CollageStage held={held} t={t} onClose={() => undefined} />)
      })
    }

    step = 'no-sound'
    render(silent)
    await waitFor(() => painted(silent, TINTS[3]!), 'silent sheet painted')
    assert('no sound: the band is not a button', band() === null)

    let index = 0
    for (const [mode, held] of modes) {
      step = `band-${mode}`
      const tint = TINTS[index++ % 3]!
      const sheet = (await voiceSheets(tint, sound, 2000)).find((item) => item.scene.music!.band!.mode === mode && item.scene.grammarId === held.scene.grammarId)!
      render(sheet)
      await waitFor(() => painted(sheet, tint), `${mode} sheet painted`)
      const button = band()
      assert(`${mode}: the band is a button`, button !== null)
      if (button === null) continue
      assert(`${mode}: named "listen"`, button.getAttribute('aria-label') === t('voiceListen') && button.title === t('voiceListen') && button.getAttribute('aria-pressed') === 'false')
      const music = held.scene.music!
      const box = stageCanvas().getBoundingClientRect()
      const scale = box.width / held.scene.canvasWidth
      const want = (rect: { x: number; y: number; width: number; height: number }) => ({ left: box.left + rect.x * scale, top: box.top + rect.y * scale, width: rect.width * scale, height: rect.height * scale })
      const hit = button.getBoundingClientRect()
      assert(`${mode}: the whole band is the button`, near(hit, want(music.region)), JSON.stringify({ hit: [hit.left, hit.top, hit.width, hit.height], want: want(music.region) }))
      const photoHits = [...document.querySelectorAll('.stage .sheet-front button.hit')].map((item) => item.getBoundingClientRect())
      assert(`${mode}: the band covers no photo`, photoHits.length >= 3 && photoHits.every((rect) => !intersects(rect, hit)))
      assert(`${mode}: no pause glyph before playing`, glyph() === null)
      await pose(`${mode}-idle`, statusNode)

      step = `play-${mode}`
      button.click()
      await waitFor(() => button.getAttribute('aria-pressed') === 'true', `${mode} playing`, 5000)
      await waitFor(() => (audio()?.currentTime ?? 0) > 0.15, `${mode} sound advancing`, 5000)
      assert(`${mode}: playing is named "pause"`, button.getAttribute('aria-label') === t('voicePause'))
      const pause = glyph()
      assert(`${mode}: a pause glyph covers the play button`, pause !== null && near(pause.getBoundingClientRect(), want(music.band!.play)), pause ? JSON.stringify(pause.getBoundingClientRect()) : 'none')
      await pose(`${mode}-playing`, statusNode)

      step = `pause-${mode}`
      button.click()
      await waitFor(() => button.getAttribute('aria-pressed') === 'false', `${mode} paused`, 5000)
      const at = audio()!.currentTime
      assert(`${mode}: pause keeps the place`, at > 0.15 && audio()!.paused, String(at))
      assert(`${mode}: paused shows the card's own play button`, glyph() === null)

      step = `end-${mode}`
      button.click()
      await waitFor(() => button.getAttribute('aria-pressed') === 'true', `${mode} resumed`, 5000)
      await waitFor(() => button.getAttribute('aria-pressed') === 'false', `${mode} ended`, 6000)
      assert(`${mode}: the end resets to the start`, audio()!.currentTime === 0 && glyph() === null, String(audio()!.currentTime))
    }

    step = 'another-sheet'
    const first = sheets[0]!
    render(first)
    await waitFor(() => painted(first, TINTS[0]!), 'first sheet painted again')
    band()!.click()
    await waitFor(() => band()!.getAttribute('aria-pressed') === 'true', 'playing before the switch', 5000)
    const playing = audio()!
    render(silent)
    await waitFor(() => painted(silent, TINTS[3]!), 'silent sheet painted again')
    assert('another sheet stops the sound', playing.paused && band() === null)

    report.pass = failures.length === 0
  } catch (error) {
    fail('stage voice connected harness', String(error) + ' | step=' + step)
  }
  ;(window as unknown as { __stageVoice?: unknown }).__stageVoice = { assertions, failures, report }
  statusNode.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
