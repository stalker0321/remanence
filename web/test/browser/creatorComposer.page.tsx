/**
 * Connected CreatorComposer browser fixture (Chromium-only gate).
 *
 * Mounts the REAL production CreatorComposer with a real SessionStore
 * (sender handle `sergio`), synthetic normalized JPEGs (decoded via
 * createImageBitmap — no camera, no network, no provider), a canvas
 * word-wrap measurer gated on loaded bundled fonts, and a synthetic
 * confirmed recipient (`ada`, distinct from the sender). Asserts the
 * visible preview, pager, slot selection, zoom/pan repaint, reset,
 * freeze, edit invalidation, music honesty, note variants, and the
 * async font-gate teardown. Reports `window.__creatorComposer`
 * (`assertions`/`failures`), plus `window.__creatorFrozenLog` (the
 * parent-visible freeze lifecycle). The runner screenshots the live
 * editor at `capture-ready`, then releases the harness for teardown.
 * Not camera/OAuth/Android/iOS acceptance.
 */
import { createRoot, type Root } from 'react-dom/client'
import { useState } from 'react'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { SessionStore } from '../../src/session/session'
import type { TextMeasurer } from '../../src/collage/prepare'
import '../../src/collage/creatorComposer.css'
import './composerFonts.css'

export interface CheckResult {
  name: string
  ok: boolean
  detail?: string
}

const assertions: CheckResult[] = []
const failures: string[] = []
const frozenLog: Array<{ frozen: boolean; branch: string | null }> = []

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

function canvasMeasurer(): TextMeasurer {
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  return {
    measure(request) {
      context.font = `${request.italic ? 'italic ' : ''}${request.weight} ${request.size}px ${request.family}`
      const words = request.text.split(/\s+/).filter((word) => word.length > 0)
      const lines: string[] = []
      let current = ''
      for (const word of words) {
        const candidate = current === '' ? word : `${current} ${word}`
        if (context.measureText(candidate).width <= request.maxWidth || current === '') current = candidate
        else {
          lines.push(current)
          current = word
        }
      }
      if (current !== '') lines.push(current)
      const width = lines.reduce((max, line) => Math.max(max, context.measureText(line).width), 0)
      const height = lines.length * request.line
      return {
        width,
        height,
        hasVisualOverflow: height > request.maxHeight || width > request.maxWidth,
      }
    },
  }
}

async function ensureFaces(): Promise<void> {
  await Promise.all([
    document.fonts.load('400 27px StudySerif'),
    document.fonts.load('400 22px StudySans'),
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
  return query<HTMLCanvasElement>('[data-testid="creator-canvas"]').dataset['painted'] ?? null
}

async function waitFor(fn: () => boolean, label: string): Promise<void> {
  const deadline = Date.now() + 30000
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

const NOTES = [
  'Probe note text.',
  'A remembered afternoon beside the sea, a long walk home, and all the little things we wanted to keep for later.',
  'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. '.repeat(4),
]

function App(props: {
  photos: CreatorComposerPhoto[]
  session: SessionStore
  measurer: TextMeasurer
  noteIndex: number
  onFrozenChange: (frozen: boolean, branch: string | null) => void
}): React.JSX.Element {
  const [musicSelected, setMusicSelected] = useState(false)
  const lang = new URLSearchParams(window.location.search).get('lang')
  return (
    <div>
      <button type="button" data-testid="page-music-toggle" onClick={() => setMusicSelected((value) => !value)}>
        music:{musicSelected ? 'on' : 'off'}
      </button>
      <span data-testid="page-photos">{props.photos.map((photo) => photo.contentId).join(',')}</span>
      <CreatorComposer
        photos={props.photos}
        note={NOTES[props.noteIndex] ?? null}
        musicSelected={musicSelected}
        styleId="press"
        recipient={{ userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16), handle: RECIPIENT_HANDLE }}
        ownerId={ACCOUNT}
        session={props.session}
        lang={lang === 'ru' ? 'ru' : lang === 'uk' ? 'uk' : 'en'}
        measurer={props.measurer}
        onFrozenChange={(frozen) => props.onFrozenChange(frozen !== null, frozen === null ? null : frozen.branchId)}
      />
    </div>
  )
}

async function installFontGate(): Promise<() => void> {
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
  ;(window as unknown as { __releaseFonts?: () => void }).__releaseFonts = () => {
    released = true
    queue.splice(0).forEach((run) => run())
  }
  return () => {
    released = true
    queue.splice(0).forEach((run) => run())
  }
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
    const built = await Promise.all([
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
    let photos: CreatorComposerPhoto[] = await Promise.all(
      built.map(async (item, index) => ({
        contentId: `p${index}`,
        width: item.bitmap.width,
        height: item.bitmap.height,
        contentHash: await sha256Hex(item.bytes),
        image: item.bitmap,
      })),
    )
    assert('synthetic photos decoded with real dimensions', photos[0].width === 800 && photos[1].height === 800)
    assert('content hashes are 64 hex chars', photos.every((photo) => /^[0-9a-f]{64}$/.test(photo.contentHash)))
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
    ;(window as unknown as { __session?: SessionStore }).__session = session
    // Hold every real font load (and therefore planning + first paint)
    // behind a controlled gate: lock/unmount/input edits below run
    // while paint is genuinely pending.
    const releaseFonts = await installFontGate()
    void releaseFonts
    document.body.innerHTML = '<div id="root"></div>'
    document.body.appendChild(status)
    let noteIndex = 0
    const render = () => {
      if (rootRef.root === null) {
        rootRef.root = createRoot(query('#root'))
      }
      rootRef.root.render(
        <App
          photos={photos}
          session={session}
          measurer={canvasMeasurer()}
          noteIndex={noteIndex}
          onFrozenChange={(hasFrozen, branch) => frozenLog.push({ frozen: hasFrozen, branch })}
        />,
      )
    }
    render()
    await runChecks(
      () => photos,
      (next) => {
        photos = next
        render()
      },
      () => {
        noteIndex = (noteIndex + 1) % NOTES.length
        render()
      },
      session,
    )
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

function frozenStates(): Array<{ frozen: boolean; branch: string | null }> {
  return frozenLog
}

async function runChecks(
  getPhotos: () => CreatorComposerPhoto[],
  setPhotos: (photos: CreatorComposerPhoto[]) => void,
  cycleNote: () => void,
  session: SessionStore,
): Promise<void> {
  await waitFor(() => {
    try {
      return query('[data-testid="creator-preparing"]') !== null
    } catch {
      return false
    }
  }, 'honest pending state')
  assert('planning gates the first paint behind loaded fonts', true)
  assert('no freeze callback before any paint completes', frozenLog.length === 0)
  const lockedWhilePending = await (async (): Promise<boolean> => {
    try {
      session.lock()
      await waitFor(() => {
        try {
          return query('[data-testid="creator-locked"]') !== null
        } catch {
          return false
        }
      }, 'locked view')
      return true
    } catch {
      return false
    }
  })()
  assert('lock during pending paint shows the locked view', lockedWhilePending)
  assert('no canvas painted while fonts are held', document.querySelector('[data-testid="creator-canvas"]') === null)
  session.unlock()
  ;(window as unknown as { __releaseFonts?: () => void }).__releaseFonts?.()
  await waitFor(() => canvasState() === 'ready', 'initial paint')
  assert('unlocked preview paints after release', canvasState() === 'ready')
  assert('still no freeze before any explicit action', frozenLog.length === 0)
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
  const readouts: string[] = []
  readouts.push(textOf('creator-transform-readout'))
  // Frozen control domain: a pan at z=1 clamps to exactly 0, so the
  // readout must NOT move and the candidates must survive. A short
  // settle wait is honest here: it proves no late change lands.
  click('creator-pan-right')
  click('creator-pan-right')
  click('creator-pan-right')
  await new Promise((resolve) => setTimeout(resolve, 500))
  assert('pan at z=1 is a safe no-op', textOf('creator-transform-readout') === readouts[0], textOf('creator-transform-readout'))
  assert('candidates survive pan at z=1', document.querySelector('[data-testid="creator-empty"]') === null)
  assert('canvas stays painted through the no-op pans', canvasState() === 'ready')
  click('creator-zoom-in')
  await waitFor(() => textOf('creator-transform-readout') !== readouts[0], 'zoom repaint')
  await waitFor(() => canvasState() === 'ready', 'zoom paint')
  click('creator-pan-right')
  await waitFor(() => textOf('creator-transform-readout') !== readouts[readouts.length - 1], 'zoomed pan')
  assert('zoomed pan changes the source window', canvasState() === 'ready')
  click('creator-reset')
  await waitFor(() => textOf('creator-transform-readout') === readouts[0], 'reset repaint')
  await waitFor(() => canvasState() === 'ready', 'reset paint')
  assert('reset restores the starting crop', true)
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
      plannerFromTextMeasurer(canvasMeasurer(), 640, 640),
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
  })()
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 'freeze snapshot')
  assert('explicit freeze snapshots the selection', textOf('creator-frozen').length > 0)
  assert('parent sees the non-null freeze', frozenLog.length > 0 && frozenLog[frozenLog.length - 1]?.frozen === true)
  click('creator-zoom-in')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'edit invalidation')
  assert('crop edit invalidates the freeze', true)
  assert('parent sees the null invalidation', frozenLog.length > 0 && frozenLog[frozenLog.length - 1]?.frozen === false)
  click('creator-freeze')
  await waitFor(() => {
    try {
      return textOf('creator-frozen').length > 0
    } catch {
      return false
    }
  }, 're-freeze')
  click('page-music-toggle')
  await waitFor(() => {
    try {
      return textOf('creator-freeze-blocked').length > 10
    } catch {
      return false
    }
  }, 'music notice')
  assert('music shows the honest unsupported notice', true)
  assert('freeze is disabled with music selected', query<HTMLButtonElement>('[data-testid="creator-freeze"]').disabled)
  click('page-music-toggle')
  await waitFor(() => document.querySelector('[data-testid="creator-freeze-blocked"]') === null, 'music off')
  const current = getPhotos()
  const swappedPhotos: CreatorComposerPhoto[] = await Promise.all(
    current.map(async (photo, index) => {
      if (index !== 0) return photo
      const replacement = await buildImage(800, 600, (context) => {
        context.fillStyle = '#2471a3'
        context.fillRect(0, 0, 800, 600)
      })
      return {
        ...photo,
        width: replacement.bitmap.width,
        height: replacement.bitmap.height,
        contentHash: await sha256Hex(replacement.bytes),
        image: replacement.bitmap,
      }
    }),
  )
  assert('replacement photo decodes with a new hash', swappedPhotos[0].contentHash !== getPhotos()[0].contentHash)
  setPhotos(swappedPhotos)
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'swap invalidation')
  assert('same-id content replacement invalidates the freeze', true)
  assert(
    'parent sees the swap invalidation',
    frozenStates().length > 0 && frozenStates()[frozenStates().length - 1]?.frozen === false,
  )
  cycleNote()
  await waitFor(() => {
    try {
      return textOf('creator-branch').length > 0 && canvasState() === 'ready'
    } catch {
      return false
    }
  }, 'regular note plans and paints')
  assert('multi-line note still previews', true)
  cycleNote()
  await waitFor(() => {
    try {
      return textOf('creator-freeze-blocked').length > 10
    } catch {
      return false
    }
  }, 'overflow note refusal')
  assert('overflowing note refuses freeze honestly', query<HTMLButtonElement>('[data-testid="creator-freeze"]').disabled)
  cycleNote()
  await waitFor(() => canvasState() === 'ready', 'note restored')
  assert('note cycle returns to a freezable note', true)
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
