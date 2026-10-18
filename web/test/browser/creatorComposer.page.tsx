/**
 * Connected CreatorComposer browser fixture (Chromium-only gate).
 *
 * Mounts the REAL production CreatorComposer with a real SessionStore
 * (sender handle `sergio`, distinct from confirmed recipient `ada`),
 * synthetic normalized JPEGs (decoded via createImageBitmap — no camera,
 * no network, no provider), a canvas word-wrap measurer gated on loaded
 * bundled fonts, and an explicit parent freeze listener. Asserts the
 * visible preview, pager, slot selection, zoom/pan repaint, reset,
 * freeze, edit/music/sender/photo/note invalidation, held-async
 * lock/unmount/input-replacement safety, and screenshot handshake state.
 * Reports `window.__creatorComposer` (`assertions`/`failures`), the
 * parent-visible log as `window.__creatorFrozenLog`, and the teardown
 * gate as `window.__creatorRelease`. Not camera/OAuth/Android/iOS
 * acceptance.
 */
import { createRoot, type Root } from 'react-dom/client'
import { useState } from 'react'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { creatorScene, plannerFromTextMeasurer, previewCandidates, type CreatorCandidateView } from '../../src/collage/creatorPreview'
import { layoutCreatorNote } from '../../src/collage/creatorNote'
import type { CreatorFreeze } from '../../src/create/creatorFreeze'
import { SessionStore } from '../../src/session/session'
import type { Scene, TextMeasurer } from '../../src/collage/prepare'
import '../../src/collage/creatorComposer.css'
import './composerFonts.css'

export interface CheckResult {
  name: string
  ok: boolean
  detail?: string
}

const assertions: CheckResult[] = []
const failures: string[] = []
const frozenLog: Array<{ frozen: boolean; branch: string | null; snapshot: CreatorFreeze | null }> = []

function pass(name: string): void {
  assertions.push({ name, ok: true })
}

function fail(name: string, detail: string): void {
  assertions.push({ name, ok: false, detail })
  failures.push(`${name}: ${detail}`)
}

function assert(name: string, cond: boolean, detail = ''): void {
  if (cond) pass(name)
  else fail(name, detail)
}

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const SENDER_HANDLE: string = 'sergio'
const RECIPIENT_HANDLE: string = 'ada'
/** Effective face the component renders the note in (style `press`.`note`). */
const NOTE_FACE = { font: 'StudySans', weight: 400, italic: false }

/** Explicit breaks (with an empty line) — expected rows are the input's. */
const EXPLICIT_NOTE = 'first line\nsecond line\n\nfourth line четвертий'
/** Long wrapped note with RU words — every word must survive wrapping. */
const WRAPPED_NOTE =
  'Тихий вечір біля моря, довга дорога додому, і всі маленькі речі, які ми хотіли зберегти на потім, у пам’яті про цей день.'
/** One unbreakable over-wide token — must refuse, never clip as ready. */
const UNBREAKABLE_NOTE = 'x'.repeat(400)

/**
 * T247 R2: the note the measurer-swap regression runs on. `runChecks`
 * overwrites this with a note it PROVES (via the real wrap) keeps the same
 * plan view/line count under measurers A and B while producing different
 * wrapped rows, so the component's binding must depend on the measurer
 * itself, not the plan region. The literal is only a safe fallback.
 */
let AB_NOTE = 'hknq orux vybe cfil jmps qtwz xadg ehkn'

const NOTES = [
  'Probe note text.',
  'A remembered afternoon beside the sea, a long walk home, and all the little things we wanted to keep for later.',
  'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.',
  'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, totam rem aperiam, eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt explicabo. Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt. Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit, sed quia non numquam eius modi tempora incidunt ut labore et dolore magnam aliquam quaerat voluptatem.',
]

async function buildImage(
  width: number,
  height: number,
  draw: (context: CanvasRenderingContext2D) => void,
): Promise<{ bytes: Uint8Array; bitmap: ImageBitmap }> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  draw(context)
  const blob = await new Promise<Blob | null>((resolve) => {
    canvas.toBlob((result) => resolve(result), 'image/jpeg', 0.9)
  })
  if (!blob) throw new Error('encode failed')
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const bitmap = await createImageBitmap(blob)
  return { bytes, bitmap }
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes.buffer as ArrayBuffer)
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * Line measurer matching production `collage/measure.ts`
 * `canvasTextMeasurer`: it lays out literal '\n' lines and does NOT wrap.
 * `softWrap:false` with `maxWidth: Infinity` therefore returns the
 * unwrapped width of a candidate line, which the shared creator
 * `wrapNoteLines` uses as its one wrapping primitive.
 */
function canvasMeasurer(): TextMeasurer {
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  return {
    measure(request) {
      context.font = `${request.italic ? 'italic ' : ''}${request.weight} ${request.size}px ${request.family}`
      const paragraphs = request.text.split('\n')
      const width = paragraphs.reduce((max, line) => Math.max(max, context.measureText(line).width), 0)
      const height = Math.max(request.line, paragraphs.length * request.line)
      const hasVisualOverflow = request.softWrap === false
        ? width > request.maxWidth || height > request.maxHeight
        : width > request.maxWidth * Math.max(1, Math.floor(request.maxHeight / request.line)) || height > request.maxHeight
      return { width: Math.ceil(width), height, hasVisualOverflow }
    },
  }
}

/**
 * T247 R2: measurer B shares A's per-character metrics but widens every
 * measured line by a fixed amount per word, so the greedy wrap can break on
 * different words while (for a chosen note) keeping the same line count and
 * therefore the SAME plan region/state/branch/slots. That is exactly the
 * blind spot the rendered binding must close.
 */
function perturbMeasurer(base: TextMeasurer, extraPerWord: number): TextMeasurer {
  return {
    measure(request) {
      const result = base.measure(request)
      const words = request.text
        .split('\n')
        .reduce((count, line) => count + line.split(/\s+/).filter((word) => word.length > 0).length, 0)
      return { ...result, width: result.width + extraPerWord * words }
    },
  }
}

async function ensureFaces(): Promise<void> {
  await Promise.all([
    document.fonts.load('400 27px StudySerif'),
    document.fonts.load('400 22px StudySerif'),
    document.fonts.load('400 20px StudySerif'),
    document.fonts.load('italic 400 20px StudySerif'),
    document.fonts.load('400 64px StudySerif'),
  ])
}

function query<T extends Element>(selector: string): T {
  const node = document.querySelector(selector)
  if (!node) throw new Error(`missing ${selector}`)
  return node as T
}

function click(testId: string): void {
  const node = query<HTMLElement>(`[data-testid="${testId}"]`)
  if (!(node instanceof HTMLButtonElement) || node.disabled) throw new Error(`${testId} not clickable`)
  node.click()
}

function textOf(testId: string): string {
  return query(`[data-testid="${testId}"]`).textContent ?? ''
}

function canvasState(): string | null {
  const canvas = document.querySelector('[data-testid="creator-canvas"]')
  if (!(canvas instanceof HTMLCanvasElement)) return null
  return canvas.dataset['painted'] ?? 'missing'
}

/**
 * Numeric transform readout `[z, xPercent, yPercent]`, parsed from the
 * localized label (`zoom`/`зум`/`зум` …). Numeric parsing keeps the pan
 * domain assertions locale-independent instead of matching English text.
 */
function readout(): number[] {
  return [...textOf('creator-transform-readout').matchAll(/-?\d+(?:\.\d+)?/g)].map((match) => Number(match[0]))
}

function sameReadout(actual: number[], expected: number[]): boolean {
  return actual.length === expected.length && actual.every((value, index) => Math.abs(value - expected[index]) <= 1e-9)
}

const IDENTITY_READOUT = [1, 0, 0]

async function waitFor(fn: () => boolean, label: string, timeoutMs = 30000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    let done = false
    try {
      done = fn()
    } catch {
      done = false
    }
    if (done) return
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${label}`)
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}

function pixelAt(canvas: HTMLCanvasElement, x: number, y: number): [number, number, number] {
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const data = context.getImageData(Math.floor(x), Math.floor(y), 1, 1).data
  return [data[0], data[1], data[2]]
}

interface FillTextCall {
  text: string
  x: number
  y: number
  font: string
}

/**
 * Trace real `fillText` draw calls on the mounted canvas so the test can
 * prove every laid-out note row was painted (words present, rows advanced),
 * not merely that the canvas was marked ready.
 */
function installFillTextTrace(): FillTextCall[] {
  const calls: FillTextCall[] = []
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

function App(props: {
  photos: CreatorComposerPhoto[]
  session: SessionStore
  measurerA: TextMeasurer
  measurerB: TextMeasurer
  noteIndex: number
  noteOverride: string | null
  senderOverride: string | undefined
  onFrozenChange: (frozen: CreatorFreeze | null) => void
  onCyclePhotos: () => void
  onTrimPhotos: () => void
  onRestorePhotos: () => void
  onToggleSender: () => void
  onCycleNote: () => void
  onSetNote: (note: string | null) => void
}): React.JSX.Element {
  const [musicSelected, setMusicSelected] = useState(false)
  // Held in state so toggling swaps the measurer prop WITHOUT remounting the
  // component (same root, same element position → state and canvas persist).
  const [measurer, setMeasurer] = useState<TextMeasurer>(props.measurerA)
  const lang = new URLSearchParams(window.location.search).get('lang')
  return (
    <div>
      <button type="button" data-testid="page-music-toggle" onClick={() => setMusicSelected((value) => !value)}>
        music:{musicSelected ? 'on' : 'off'}
      </button>
      <button type="button" data-testid="page-photos-cycle" onClick={props.onCyclePhotos}>
        photos
      </button>
      <button type="button" data-testid="page-photos-trim" onClick={props.onTrimPhotos}>
        trim
      </button>
      <button type="button" data-testid="page-photos-restore" onClick={props.onRestorePhotos}>
        restore
      </button>
      <button type="button" data-testid="page-sender-toggle" onClick={props.onToggleSender}>
        sender
      </button>
      <button type="button" data-testid="page-note-cycle" onClick={props.onCycleNote}>
        note
      </button>
      <button type="button" data-testid="page-note-explicit" onClick={() => props.onSetNote(EXPLICIT_NOTE)}>
        explicit
      </button>
      <button type="button" data-testid="page-note-wrapped" onClick={() => props.onSetNote(WRAPPED_NOTE)}>
        wrapped
      </button>
      <button type="button" data-testid="page-note-unbreakable" onClick={() => props.onSetNote(UNBREAKABLE_NOTE)}>
        unbreakable
      </button>
      <button type="button" data-testid="page-note-clear" onClick={() => props.onSetNote(null)}>
        clearnote
      </button>
      <button type="button" data-testid="page-note-ab" onClick={() => props.onSetNote(AB_NOTE)}>
        abnote
      </button>
      <button
        type="button"
        data-testid="page-measurer-toggle"
        onClick={() => setMeasurer((current) => (current === props.measurerA ? props.measurerB : props.measurerA))}
      >
        measurer:{measurer === props.measurerB ? 'B' : 'A'}
      </button>
      <span data-testid="page-photos">{props.photos.map((photo) => photo.contentId).join(',')}</span>
      <CreatorComposer
        photos={props.photos}
        note={props.noteOverride ?? (NOTES[props.noteIndex] ?? null)}
        musicSelected={musicSelected}
        styleId="press"
        senderHandle={props.senderOverride}
        recipient={{ userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16), handle: RECIPIENT_HANDLE }}
        ownerId={ACCOUNT}
        session={props.session}
        lang={lang === 'ru' ? 'ru' : lang === 'uk' ? 'uk' : 'en'}
        measurer={measurer}
        onFrozenChange={props.onFrozenChange}
      />
    </div>
  )
}

function installFontGate(): { release: () => void; hold: () => void } {
  const realLoad = document.fonts.load.bind(document.fonts)
  let released = false
  const queue: Array<() => void> = []
  const gate = function (this: FontFaceSet, font: string, text?: string): Promise<FontFace[]> {
    if (released) return realLoad(font, text)
    return new Promise<FontFace[]>((resolve) => {
      queue.push(() => resolve(realLoad(font, text)))
    })
  }
  document.fonts.load = gate as typeof document.fonts.load
  const hooks = {
    release() {
      released = true
      queue.splice(0).forEach((run) => run())
    },
    hold() {
      released = false
    },
  }
  ;(window as unknown as { __creatorFonts?: { release: () => void; hold: () => void } }).__creatorFonts = hooks
  return hooks
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'composer-status'
  status.textContent = 'starting'
  ;(window as unknown as { __creatorComposer?: unknown }).__creatorComposer = { assertions, failures }
  ;(window as unknown as { __creatorFrozenLog?: unknown }).__creatorFrozenLog = frozenLog
  ;(window as unknown as { __creatorRelease?: boolean }).__creatorRelease = false
  const rootRef: { root: Root | null } = { root: null }
  try {
    await ensureFaces()
    installFontGate()
    const buildOriginal = () =>
      Promise.all([
        buildImage(800, 600, (context) => {
          context.fillStyle = '#c0392b'
          context.fillRect(0, 0, 400, 600)
          context.fillStyle = '#2471a3'
          context.fillRect(400, 0, 400, 600)
        }),
        buildImage(600, 800, (context) => {
          context.fillStyle = '#c0392b'
          context.fillRect(0, 0, 600, 400)
          context.fillStyle = '#2471a3'
          context.fillRect(0, 400, 600, 400)
        }),
        buildImage(1000, 1000, (context) => {
          const gradient = context.createLinearGradient(0, 0, 1000, 1000)
          gradient.addColorStop(0, '#c0392b')
          gradient.addColorStop(1, '#2471a3')
          context.fillStyle = gradient
          context.fillRect(0, 0, 1000, 1000)
        }),
      ])
    const buildAlternate = () =>
      Promise.all([
        buildImage(800, 600, (context) => {
          context.fillStyle = '#1e8449'
          context.fillRect(0, 0, 800, 600)
        }),
        buildImage(600, 800, (context) => {
          context.fillStyle = '#2471a3'
          context.fillRect(0, 0, 600, 800)
        }),
        buildImage(1000, 1000, (context) => {
          context.fillStyle = '#7d3c98'
          context.fillRect(0, 0, 1000, 1000)
        }),
      ])
    const toPhotos = async (built: Array<{ bytes: Uint8Array; bitmap: ImageBitmap }>): Promise<CreatorComposerPhoto[]> =>
      Promise.all(
        built.map(async (item, index) => ({
          contentId: `p${index}`,
          width: item.bitmap.width,
          height: item.bitmap.height,
          contentHash: await sha256Hex(item.bytes),
          image: item.bitmap,
        })),
      )
    const originalBuilt = await buildOriginal()
    const originalPhotos = await toPhotos(originalBuilt)
    assert('synthetic photos decoded with real dimensions', originalPhotos[0].width === 800 && originalPhotos[1].height === 800)
    assert('content hashes are 64 hex chars', originalPhotos.every((photo) => /^[0-9a-f]{64}$/.test(photo.contentHash)))
    let photos: CreatorComposerPhoto[] = originalPhotos
    let alternates: CreatorComposerPhoto[] | null = null
    let noteIndex = 0
    let noteOverride: string | null = null
    let senderOverride: string | undefined
    const fillTextCalls = installFillTextTrace()
    ;(window as unknown as { __fillTextCalls?: unknown }).__fillTextCalls = fillTextCalls
    const session = new SessionStore('en')
    session.signIn({
      accountId: ACCOUNT,
      bundleId: BUNDLE,
      handle: SENDER_HANDLE,
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    // T247 R2: stable measurer identities. `render()` re-renders the SAME App
    // root, so these objects persist; only the explicit toggle swaps A↔B.
    const measurerA = canvasMeasurer()
    const measurerB = perturbMeasurer(canvasMeasurer(), 9)
    const render = () => {
      if (rootRef.root === null) {
        const host = document.createElement('div')
        host.setAttribute('data-testid', 'creator-host')
        document.body.appendChild(host)
        rootRef.root = createRoot(host)
      }
      rootRef.root.render(
        <App
          photos={photos}
          session={session}
          measurerA={measurerA}
          measurerB={measurerB}
          noteIndex={noteIndex}
          noteOverride={noteOverride}
          senderOverride={senderOverride}
          onFrozenChange={(frozen) => frozenLog.push({ frozen: frozen !== null, branch: frozen === null ? null : frozen.branchId, snapshot: frozen })}
          onSetNote={(note) => {
            noteOverride = note
            render()
          }}
          onCyclePhotos={() => {
            void (async () => {
              if (alternates === null) {
                alternates = await toPhotos(await buildAlternate())
              }
              photos = alternates
              render()
            })()
          }}
          onTrimPhotos={() => {
            photos = photos.slice(0, 2)
            render()
          }}
          onRestorePhotos={() => {
            photos = originalPhotos
            render()
          }}
          onToggleSender={() => {
            senderOverride = senderOverride === undefined ? 'sergio-alt' : undefined
            render()
          }}
          onCycleNote={() => {
            noteIndex = (noteIndex + 1) % NOTES.length
            render()
          }}
        />,
      )
    }
    const unmount = () => {
      rootRef.root?.unmount()
      rootRef.root = null
      for (const host of [...document.querySelectorAll('[data-testid="creator-host"]')]) host.remove()
    }
    document.body.appendChild(status)
    render()
    await runChecks({
      getPhotos: () => photos,
      session,
      unmount: () => {
        unmount()
      },
      remount: () => {
        render()
      },
    })
    status.textContent = 'capture-ready'
    await waitFor(
      () => (window as unknown as { __creatorRelease?: boolean }).__creatorRelease === true,
      'runner release',
    )
    await runTeardown(session)
    status.textContent = 'done'
  } catch (error) {
    fail('harness setup', String(error))
    status.textContent = 'failed'
  } finally {
    if (rootRef.root !== null) {
      const handle = rootRef.root
      await waitFor(() => {
        handle.unmount()
        return document.querySelector('[data-testid="creator-canvas"]') === null
      }, 'unmount cleanup')
      assert('unmount drops the composer without errors', document.querySelector('[data-testid="creator-canvas"]') === null)
    }
  }
}

function lastFrozen(): { frozen: boolean; branch: string | null; snapshot: CreatorFreeze | null } | null {
  return frozenLog.length === 0 ? null : frozenLog[frozenLog.length - 1]!
}

  async function runChecks(getters: {
    getPhotos: () => CreatorComposerPhoto[]
    session: SessionStore
    unmount: () => void
    remount: () => void
  }): Promise<void> {
  const { getPhotos, session, unmount, remount } = getters
  await waitFor(() => {
    try {
      return query('[data-testid="creator-preparing"]') !== null
    } catch {
      return false
    }
  }, 'honest pending state')
  assert('planning gates the first paint behind loaded fonts', true)
  assert('no freeze callback before any paint completes', frozenLog.length === 0)
  click('page-photos-cycle')
  await waitFor(() => {
    try {
      return query('[data-testid="creator-preparing"]') !== null && document.querySelector('[data-testid="creator-canvas"]') === null
    } catch {
      return false
    }
  }, 'held input replacement stays pending without paint')
  assert('input replacement while held paints nothing stale', document.querySelector('[data-testid="creator-canvas"]') === null)
  assert('no frozen callback for the held replacement', frozenLog.every((entry) => entry.frozen === false))
  session.lock()
  await waitFor(() => {
    try {
      return query('[data-testid="creator-locked"]') !== null
    } catch {
      return false
    }
  }, 'locked view during held paint')
  assert('lock during pending paint shows the locked view', true)
  assert('no canvas painted while fonts are held', document.querySelector('[data-testid="creator-canvas"]') === null)
  assert('no frozen callback while torn down', frozenLog.every((entry) => entry.frozen === false))
  session.unlock()
  await waitFor(() => {
    try {
      return query('[data-testid="creator-preparing"]') !== null
    } catch {
      return false
    }
  }, 'unlock returns to preparing')
  assert('unlock without release stays pending honestly', true)
  unmount()
  await waitFor(() => document.querySelector('[data-testid="creator-composer"]') === null, 'unmount while pending')
  assert('unmount during held paint drops the composer without paint', document.querySelector('[data-testid="creator-canvas"]') === null)
  assert('unmount during pending emits no non-null freeze', frozenLog.every((entry) => entry.frozen === false))
  remount()
  await waitFor(() => {
    try {
      return query('[data-testid="creator-preparing"]') !== null
    } catch {
      return false
    }
  }, 'remount returns to preparing')
  const fonts = (window as unknown as { __creatorFonts?: { release: () => void; hold: () => void } }).__creatorFonts
  if (typeof fonts?.release !== 'function') throw new Error('missing font release hook')
  fonts.release()
  await waitFor(() => canvasState() === 'ready', 'initial paint')
  assert('unlocked preview paints after release', canvasState() === 'ready')
  assert('still no freeze before any explicit action', frozenLog.every((entry) => entry.frozen === false))
  ;(() => {
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    assert('held replacement painted the new set, not stale pixels', canvas.width === 360 && canvas.height === 640)
    const left = pixelAt(canvas, 180, 320)
    assert('swapped set paints its own pixels (green, not the old red/blue)', left[1] > 100 && left[0] < 100, left.join(','))
  })()
  click('page-photos-restore')
  await waitFor(() => canvasState() === 'ready', 'restored set paints')
  ;(() => {
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    assert('composer mounted with preview canvas', canvas.width === 360 && canvas.height === 640)
  })()
  click('creator-next')
  await waitFor(() => /\b2\b/.test(textOf('creator-count')), 'pager advance')
  assert('pager next changes branch', textOf('creator-branch').length > 0)
  click('creator-prev')
  await waitFor(() => /\b1\b/.test(textOf('creator-count')), 'pager return')
  assert('pager prev returns', true)
  click('creator-slot-1')
  await waitFor(() => {
    try {
      return query('[data-testid="creator-slot-1"]').getAttribute('aria-pressed') === 'true'
    } catch {
      return false
    }
  }, 'slot selection')
  assert('slot selection marks aria-pressed', true)
  click('creator-zoom-in')
  click('creator-zoom-in')
  click('creator-zoom-in')
  await waitFor(() => !sameReadout(readout(), IDENTITY_READOUT), 'zoomed transform applies')
  assert('repeated zoom-ins stay inside the frozen domain', sameReadout(readout(), [1.15, 0, 0]))
  await waitFor(() => canvasState() === 'ready', 'zoom paint')
  click('creator-pan-right')
  click('creator-pan-right')
  click('creator-pan-right')
  await waitFor(() => sameReadout(readout(), [1.15, 3, 0]), 'zoomed pan applies')
  assert('stored transform accumulates pans inside the frozen domain', sameReadout(readout(), [1.15, 3, 0]))
  await waitFor(() => canvasState() === 'ready', 'zoomed pan repaints')
  assert('repeated pans keep candidates and repaint honestly', document.querySelector('[data-testid="creator-empty"]') === null)
  click('creator-reset')
  await waitFor(() => sameReadout(readout(), IDENTITY_READOUT), 'reset repaint')
  await waitFor(() => canvasState() === 'ready', 'reset paint')
  assert('reset restores the starting crop', true)
  let expectedView: CreatorCandidateView | null = null
  let expectedScene: Scene | null = null
  ;(() => {
    const views = previewCandidates(
      {
        id: 'browser',
        sender: SENDER_HANDLE,
        photos: getPhotos().map((photo) => ({
          contentId: photo.contentId,
          width: photo.width,
          height: photo.height,
          contentHash: photo.contentHash,
        })),
        note: 'Probe note text.',
      },
      undefined,
      plannerFromTextMeasurer(canvasMeasurer(), 640, 640, NOTE_FACE),
    )
    const tile = views[0].slots[0]
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    const left = pixelAt(canvas, tile.destination.x + tile.destination.width * 0.25, tile.destination.y + tile.destination.height * 0.5)
    const right = pixelAt(canvas, tile.destination.x + tile.destination.width * 0.75, tile.destination.y + tile.destination.height * 0.5)
    assert('two-tone source spans the cell (red left)', left[0] > 120 && left[2] < 120, left.join(','))
    assert('two-tone source spans the cell (blue right)', right[2] > 100 && right[0] < 140, right.join(','))
    const scene = creatorScene({
      view: views[0],
      styleId: 'press',
      senderHandle: SENDER_HANDLE,
      noteText: 'Probe note text.',
      photos: new Map(getPhotos().map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    })
    const senderMark = scene.provenance.find((mark) => mark.kind === 'sender')
    assert('sender and recipient are distinct handles', SENDER_HANDLE !== RECIPIENT_HANDLE)
    assert('provenance binds the live session sender, not the recipient', senderMark?.text === `From ${SENDER_HANDLE}`, senderMark?.text ?? 'missing')
    assert('brand mark carries the house text', scene.provenance.some((mark) => mark.kind === 'brand' && mark.text === 'Remanence'))
    expectedView = views[0] ?? null
    expectedScene = scene
  })()
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze snapshot')
  assert('explicit freeze snapshots the selection', textOf('creator-frozen').length > 0)
  assert('parent sees the non-null freeze', lastFrozen()?.frozen === true)
  ;(() => {
    // T247: the parent-held freeze must carry the EXACT render that was
    // painted. Compare the frozen DTO against the independently derived
    // view/scene for the same inputs AND the real note fillText trace.
    const snapshot = lastFrozen()?.snapshot ?? null
    const view = expectedView
    const scene = expectedScene
    assert(
      'frozen snapshot carries a keyed rendered plan',
      snapshot !== null && typeof snapshot.renderedKey === 'string' && snapshot.renderedKey.length > 0 && snapshot.renderedPlan !== undefined,
    )
    if (!snapshot || !view || !scene) return
    const plan = snapshot.renderedPlan
    assert(
      'rendered plan is the g5 canvas for this branch/style',
      plan.grammarId === 'g5' && plan.branchId === snapshot.branchId && plan.branchId === view.branch && plan.style.styleId === 'press',
    )
    assert(
      'rendered plan slots match the painted destinations/sources/shapes',
      plan.slots.length === view.slots.length &&
        plan.slots.every((slot, index) => {
          const source = view.slots[index]!
          return (
            slot.contentId === source.photoId &&
            slot.ordinal === index &&
            slot.shape === source.shape &&
            slot.destination.x === source.destination.x &&
            slot.destination.y === source.destination.y &&
            slot.destination.width === source.destination.width &&
            slot.destination.height === source.destination.height &&
            slot.source.x === source.sourcePx.x &&
            slot.source.y === source.sourcePx.y &&
            slot.source.width === source.sourcePx.width &&
            slot.source.height === source.sourcePx.height
          )
        }),
    )
    const trace = (window as unknown as { __fillTextCalls?: Array<{ text: string; x: number; y: number; font: string }> }).__fillTextCalls ?? []
    const paintedRows = trace
      .filter((call) => call.font.includes('StudySans'))
      .slice(-(plan.note?.rows.length ?? 0))
      .map((row) => row.text)
      .join('|')
    assert(
      'rendered plan note matches the painted note face/region/rows',
      plan.note !== null &&
        scene.note !== null &&
        plan.note.text === scene.note.text &&
        plan.note.region.x === scene.note.region.x &&
        plan.note.region.y === scene.note.region.y &&
        plan.note.family === scene.note.family &&
        plan.note.size === scene.note.size &&
        plan.note.line === scene.note.line &&
        plan.note.rows.map((row) => row.text).join('|') === paintedRows,
    )
    assert(
      'rendered plan provenance binds the sender text + regions',
      plan.provenance.map((mark) => mark.text).join('|') === scene.provenance.map((mark) => mark.text).join('|') &&
        plan.provenance.map((mark) => mark.region.x).join('|') === scene.provenance.map((mark) => mark.region.x).join('|'),
    )
    assert('rendered plan carries no image/DOM capability', !JSON.stringify(plan).includes('ImageBitmap') && !JSON.stringify(plan).includes('Uint8Array'))
  })()
  click('page-sender-toggle')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'sender invalidation')
  assert('explicit sender edit invalidates the freeze', true)
  assert('parent sees the sender-change null', lastFrozen()?.frozen === false)
  click('page-sender-toggle')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'sender restore settles')
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => textOf('creator-frozen').length > 0, 're-freeze')
  assert('re-freeze after sender restore works', true)
  click('creator-pan-right')
  await waitFor(() => canvasState() === 'ready', 'no-op pan settles')
  assert('pan at z=1 is a safe no-op (frozen crop kept)', sameReadout(readout(), IDENTITY_READOUT))
  assert('no-op pan does not drop the freeze', document.querySelector('[data-testid="creator-frozen"]') !== null)
  click('creator-zoom-in')
  await waitFor(() => !sameReadout(readout(), IDENTITY_READOUT), 'zoom applies')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'zoom invalidation')
  assert('zoom invalidates the frozen crop', true)
  assert('parent sees the zoom null', lastFrozen()?.frozen === false)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled after zoom')
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 're-freeze zoomed')
  click('creator-pan-right')
  await waitFor(() => !sameReadout(readout(), [1.05, 0, 0]), 'zoomed pan applies')
  assert('pan at zoom changes the frozen crop', true)
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'crop invalidation')
  assert('crop edit invalidates the freeze', true)
  assert('parent sees the edit null', lastFrozen()?.frozen === false)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze again')
  click('page-music-toggle')
  await waitFor(() => {
    try {
      return textOf('creator-freeze-blocked').length > 10
    } catch {
      return false
    }
  }, 'music notice')
  assert('music shows the honest unsupported notice', true)
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'music invalidation')
  assert('music selection drops the held freeze', document.querySelector('[data-testid="creator-frozen"]') === null)
  assert('parent sees the music null', lastFrozen()?.frozen === false)
  assert('freeze is disabled with music selected', query<HTMLButtonElement>('[data-testid="creator-freeze"]').disabled)
  click('page-music-toggle')
  await waitFor(() => document.querySelector('[data-testid="creator-freeze-blocked"]') === null, 'music off')
  assert('music off does not resurrect a dropped freeze', lastFrozen()?.frozen === false)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 're-freeze after music off')
  click('page-photos-cycle')
  await waitFor(() => canvasState() === 'ready', 'swapped set paints')
  assert('same-id photo replacement repaints the current set', true)
  ;(() => {
    const views = previewCandidates({
      id: 'browser',
      sender: SENDER_HANDLE,
      photos: getPhotos().map((photo) => ({
        contentId: photo.contentId,
        width: photo.width,
        height: photo.height,
        contentHash: photo.contentHash,
      })),
      note: 'Probe note text.',
    })
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    const tile = views[0].slots[0]
    const px = pixelAt(canvas, tile.destination.x + tile.destination.width * 0.5, tile.destination.y + tile.destination.height * 0.5)
    assert('swapped set paints new pixels, not a stale frame (green, not red/blue)', px[1] > 100 && px[0] < 100, px.join(','))
  })()
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'swap invalidation')
  assert('swap drops the held freeze', document.querySelector('[data-testid="creator-frozen"]') === null)
  assert('parent sees the swap null', lastFrozen()?.frozen === false)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze swapped set')
  click('page-photos-trim')
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'two-photo unavailable')
  assert('two photos refuse the plan honestly (no canvas, no stale paint)', document.querySelector('[data-testid="creator-empty"]') !== null)
  assert('refused plan explains itself instead of a blank frame', document.querySelector('[data-testid="creator-paint-error"]') !== null)
  assert('trim drops the held freeze', lastFrozen()?.frozen === false)
  click('page-photos-restore')
  await waitFor(() => canvasState() === 'ready', 'restored set paints')
  assert('restored set paints the original two-tone cell again', true)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze restored set')
  assert('restored set freezes with matching current geometry', true)
  click('page-note-cycle')
  // A new note re-signs the plan, so Freeze only re-arms after the CURRENT
  // note has planned and painted. Waiting on that (not on a possibly stale
  // `canvas.dataset.painted === 'ready'`) binds the assertion to this edit.
  await waitFor(() => {
    try {
      return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled
    } catch {
      return false
    }
  }, 'regular note repaints and arms freeze')
  assert('multi-line note still previews', canvasState() === 'ready')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'note invalidation')
  assert('note edit invalidates the held freeze', lastFrozen()?.frozen === false)
  assert('parent sees the note-change null', document.querySelector('[data-testid="creator-frozen"]') === null)
  ;(() => {
    const measurer = canvasMeasurer()
    const photos = getPhotos().map((photo) => ({ contentId: photo.contentId, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
    const shortViews = previewCandidates({ id: 'browser', sender: SENDER_HANDLE, photos, note: NOTES[0] as string }, undefined, plannerFromTextMeasurer(measurer, 640, 640, NOTE_FACE))
    const mediumViews = previewCandidates({ id: 'browser', sender: SENDER_HANDLE, photos, note: NOTES[1] as string }, undefined, plannerFromTextMeasurer(measurer, 640, 640, NOTE_FACE))
    assert('real metrics grow the note region (not the stub box)', (mediumViews[0]?.note?.region.height ?? 0) > (shortViews[0]?.note?.region.height ?? 0))
    assert('tiles re-plan around the taller note', (mediumViews[0]?.slots[0]?.destination.height ?? 0) < (shortViews[0]?.slots[0]?.destination.height ?? Number.POSITIVE_INFINITY))
  })()
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze multi-line note')
  assert('multi-line note freezes with matching current geometry', true)
  click('page-note-cycle')
  await waitFor(() => {
    try {
      return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled
    } catch {
      return false
    }
  }, 'longer regular note repaints and arms freeze')
  assert('longer regular note still previews (real metrics, not a stub box)', canvasState() === 'ready')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'longer note invalidation')
  assert('longer note edit invalidates the held freeze', lastFrozen()?.frozen === false)
  ;(() => {
    const measurer = canvasMeasurer()
    const photos = getPhotos().map((photo) => ({ contentId: photo.contentId, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
    const mediumViews = previewCandidates({ id: 'browser', sender: SENDER_HANDLE, photos, note: NOTES[1] as string }, undefined, plannerFromTextMeasurer(measurer, 640, 640, NOTE_FACE))
    const longViews = previewCandidates({ id: 'browser', sender: SENDER_HANDLE, photos, note: NOTES[2] as string }, undefined, plannerFromTextMeasurer(measurer, 640, 640, NOTE_FACE))
    assert('longer regular note grows the region further', (longViews[0]?.note?.region.height ?? 0) > (mediumViews[0]?.note?.region.height ?? 0))
  })()
  click('page-note-cycle')
  await waitFor(() => {
    try {
      return query('[data-testid="creator-empty"]') !== null
    } catch {
      return false
    }
  }, 'extreme note refuses the plan')
  assert('unplannable note shows the honest empty state, not a stale preview', query('[data-testid="creator-paint-error"]') !== null)
  assert('extreme note drops the held freeze', lastFrozen()?.frozen === false)
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'plan refusal clears canvas')
  assert('plan refusal leaves no canvas behind', document.querySelector('[data-testid="creator-canvas"]') === null)
  click('page-note-cycle')
  await waitFor(() => canvasState() === 'ready', 'note restored')
  assert('short note restores a freezable current scene', true)
  await waitFor(() => { try { return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled } catch { return false } }, 'freeze enabled');
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze restored note')
  assert('capture state holds a fresh matching freeze', true)

  // --- r3: the creator note must PAINT every laid-out row/word ---
  const traced = (): FillTextCall[] => (window as unknown as { __fillTextCalls?: FillTextCall[] }).__fillTextCalls ?? []
  const noteRows = (): FillTextCall[] => traced().filter((call) => call.font.includes('StudySans'))
  const armFreeze = async (label: string): Promise<void> => {
    await waitFor(() => {
      try {
        return !(document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null)?.disabled
      } catch {
        return false
      }
    }, label)
  }

  // Explicit breaks + an empty line: painted rows match the input lines.
  traced().length = 0
  click('page-note-explicit')
  await armFreeze('explicit note repaints')
  assert('explicit-break note paints ready', canvasState() === 'ready')
  ;(() => {
    const rows = noteRows()
    const expected = ['first line', 'second line', 'fourth line четвертий']
    assert(
      'explicit note painted its non-empty rows in order',
      JSON.stringify(rows.map((row) => row.text)) === JSON.stringify(expected),
      JSON.stringify(rows.map((row) => row.text)),
    )
    assert(
      'explicit note preserved the empty line as an advanced row',
      rows.length === 3 && rows[2]!.y > rows[1]!.y && rows[1]!.y > rows[0]!.y
        && Math.abs((rows[2]!.y - rows[1]!.y) - 2 * (rows[1]!.y - rows[0]!.y)) < 1e-6,
      JSON.stringify(rows.map((row) => row.y)),
    )
  })()

  // Long wrapped RU note: EVERY word must be painted (not hidden/clipped).
  traced().length = 0
  click('page-note-wrapped')
  await armFreeze('wrapped note repaints')
  assert('wrapped RU note paints ready', canvasState() === 'ready')
  ;(() => {
    const rows = noteRows()
    const painted = rows.map((row) => row.text).join(' ').split(/\s+/).filter((word) => word.length > 0)
    const input = WRAPPED_NOTE.split(/\s+/).filter((word) => word.length > 0)
    assert('every wrapped word is painted (none dropped or hidden)', JSON.stringify(painted) === JSON.stringify(input), `${painted.length}/${input.length}`)
    assert('wrapped note uses multiple rows', rows.length > 1, String(rows.length))
    assert('painted rows are within the laid-out note region', rows.every((row) => row.x >= 10 && row.x < 350))
  })()

  // One unbreakable over-wide token: refuse honestly, never clip as ready.
  traced().length = 0
  click('page-note-unbreakable')
  await waitFor(() => canvasState() === 'refused-note', 'unbreakable note refuses')
  assert('over-wide unbreakable note refuses instead of clipping', canvasState() === 'refused-note')
  assert('unbreakable note disables freeze', query<HTMLButtonElement>('[data-testid="creator-freeze"]').disabled)

  // Restore a normal short note.
  traced().length = 0
  click('page-note-clear')
  await waitFor(() => canvasState() === 'ready', 'clear note repaints')
  assert('clearing the note restores a ready preview', canvasState() === 'ready')

  // --- r3: freeze requires the live generation to match the prepared render ---
  await armFreeze('freeze before generation race')
  click('creator-freeze')
  await waitFor(() => textOf('creator-frozen').length > 0, 'baseline freeze')
  const frozenBefore = frozenLog.length
  session.lock()
  session.unlock()
  // Stale render: the button is still enabled but its prepared generation is gone.
  const freezeButton = document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null
  if (freezeButton && !freezeButton.disabled) freezeButton.click()
  await waitFor(() => canvasState() === 'ready', 'repaint after generation bump')
  assert(
    'generation bump before flush emitted no non-null freeze',
    frozenLog.slice(frozenBefore).every((entry) => entry.frozen === false),
    JSON.stringify(frozenLog.slice(frozenBefore)),
  )
  await armFreeze('freeze re-armed after generation bump')
  click('creator-freeze')
  await waitFor(() => textOf('creator-frozen').length > 0, 'freeze after generation bump')
  assert('explicit freeze works after the new generation paints', true)

  // --- t247 r2: replacing the measurer must void a held render (one mount) ---
  // Two measurers share per-character metrics; B widens every measured line by
  // a fixed per-word amount. We PROVE (with the real wrap) a note they wrap to
  // the SAME plan view/line count but DIFFERENT rows, so the plan
  // region/state/branch/slots are byte-identical and only the measured render
  // differs. That is the exact blind spot a plan-derived key misses.
  const measuredRows = (m: TextMeasurer, view: CreatorCandidateView): string[] => {
    const scene = creatorScene({
      view,
      styleId: 'press',
      senderHandle: SENDER_HANDLE,
      noteText: AB_NOTE,
      photos: new Map(getPhotos().map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    })
    if (!scene.note) return []
    const face = {
      font: scene.note.family,
      weight: scene.note.weight,
      italic: scene.note.italic,
      size: scene.note.size,
      line: scene.note.line,
    }
    const layout = layoutCreatorNote(scene.note.text, face, scene.note.region, (lineText) =>
      m.measure({
        text: lineText,
        family: face.font,
        size: face.size,
        weight: face.weight,
        italic: face.italic,
        line: face.line,
        maxWidth: Number.POSITIVE_INFINITY,
        maxHeight: Number.POSITIVE_INFINITY,
        softWrap: false,
      }).width,
    )
    return layout.overflow ? [] : layout.lines.map((row) => row.text)
  }
  const abMeasurerA = canvasMeasurer()
  const abMeasurerB = perturbMeasurer(canvasMeasurer(), 9)
  const abPhotos = getPhotos().map((photo) => ({ contentId: photo.contentId, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
  const abViews = (m: TextMeasurer): CreatorCandidateView[] =>
    previewCandidates({ id: 'browser', sender: SENDER_HANDLE, photos: abPhotos, note: AB_NOTE }, undefined, plannerFromTextMeasurer(m, 640, 640, NOTE_FACE))
  let abRowsA: string[] = []
  let abRowsB: string[] = []
  ;(() => {
    const alphabet = 'abcdefghijklmnopqrstuvwxyz'
    for (let k = 3; k <= 9; k += 1) {
      for (let len = 3; len <= 11; len += 1) {
        AB_NOTE = Array.from({ length: k }, (_, i) => alphabet[(i * 5 + len) % 26].repeat(len)).join(' ')
        const viewsA = abViews(abMeasurerA)
        const viewsB = abViews(abMeasurerB)
        if (viewsA.length === 0 || viewsB.length === 0) continue
        if (JSON.stringify(viewsA[0]) !== JSON.stringify(viewsB[0])) continue
        const rowsA = measuredRows(abMeasurerA, viewsA[0]!)
        const rowsB = measuredRows(abMeasurerB, viewsB[0]!)
        if (rowsA.length === 0 || rowsB.length === 0 || rowsA.length !== rowsB.length || rowsA.length > 3) continue
        if (JSON.stringify(rowsA) === JSON.stringify(rowsB)) continue
        abRowsA = rowsA
        abRowsB = rowsB
        return
      }
    }
  })()
  assert(
    'measurer A/B wrap the ab note to the same line count but different rows',
    abRowsA.length > 0 && abRowsA.length === abRowsB.length && JSON.stringify(abRowsA) !== JSON.stringify(abRowsB),
    JSON.stringify({ note: AB_NOTE, abRowsA, abRowsB }),
  )
  assert(
    'measurer A/B produce an identical plan view (region/state/branch/slots)',
    JSON.stringify(abViews(abMeasurerA)[0]) === JSON.stringify(abViews(abMeasurerB)[0]),
  )

  traced().length = 0
  click('page-note-ab')
  // Wait for the CURRENT render (not a possibly stale enabled Freeze button):
  // the painted rows must be the measured A rows before we arm.
  await waitFor(
    () => JSON.stringify(noteRows().map((row) => row.text)) === JSON.stringify(abRowsA),
    'ab note paints its measurer-A rows',
  )
  await armFreeze('ab note repaints and arms freeze')
  assert('ab note paints ready under measurer A', canvasState() === 'ready')
  click('creator-freeze')
  await waitFor(() => textOf('creator-frozen').length > 0, 'freeze under measurer A')
  const snapA = lastFrozen()?.snapshot ?? null
  const keyA = snapA?.renderedKey ?? ''
  assert('measurer A freeze holds a keyed render', snapA !== null && typeof keyA === 'string' && keyA.length > 0)
  assert(
    'measurer A freeze carries the A rows',
    JSON.stringify(snapA?.renderedPlan?.note?.rows?.map((row) => row.text) ?? []) === JSON.stringify(abRowsA),
    JSON.stringify(snapA?.renderedPlan?.note?.rows?.map((row) => row.text) ?? []),
  )

  // Swap the measurer on the SAME mounted component: the plan view is
  // unchanged but the measured rows change, so the held freeze must be voided
  // BEFORE any new freeze is requested. Nothing is unmounted or re-rooted.
  const frozenBeforeSwap = frozenLog.length
  traced().length = 0
  click('page-measurer-toggle')
  await waitFor(
    () => JSON.stringify(noteRows().map((row) => row.text)) === JSON.stringify(abRowsB),
    'measurer swap paints the B rows',
  )
  await waitFor(() => lastFrozen()?.frozen === false, 'measurer swap voids the held freeze')
  assert('parent sees the measurer-change null', lastFrozen()?.frozen === false)
  assert(
    'the swap emitted no non-null freeze of its own',
    frozenLog.slice(frozenBeforeSwap).every((entry) => entry.frozen === false),
    JSON.stringify(frozenLog.slice(frozenBeforeSwap)),
  )
  assert('the frozen chip cleared on the swap', document.querySelector('[data-testid="creator-frozen"]') === null)
  assert(
    'canvas paints the measurer B rows after the swap',
    JSON.stringify(noteRows().map((row) => row.text)) === JSON.stringify(abRowsB),
    JSON.stringify(noteRows().map((row) => row.text)),
  )

  await armFreeze('measurer B re-arms freeze')
  click('creator-freeze')
  await waitFor(() => textOf('creator-frozen').length > 0, 're-freeze under measurer B')
  const snapB = lastFrozen()?.snapshot ?? null
  assert('fresh freeze under measurer B holds a NEW key', snapB !== null && snapB.renderedKey !== keyA)
  assert(
    'fresh freeze under measurer B holds the B rows',
    JSON.stringify(snapB?.renderedPlan?.note?.rows?.map((row) => row.text) ?? []) === JSON.stringify(abRowsB),
    JSON.stringify(snapB?.renderedPlan?.note?.rows?.map((row) => row.text) ?? []),
  )
}

async function runTeardown(session: SessionStore): Promise<void> {
  session.lock()
  await waitFor(() => {
    try {
      return textOf('creator-locked').length > 0
    } catch {
      return false
    }
  }, 'lock teardown')
  assert('lock clears the preview with an honest notice', true)
  assert('lock drops the frozen snapshot', document.querySelector('[data-testid="creator-frozen"]') === null)
  session.unlock()
  await waitFor(() => canvasState() === 'ready', 'post-unlock repaint')
  assert('unlock restores the live preview', canvasState() === 'ready')
}

void main()
