/**
 * Connected CreatorComposer browser fixture (Chromium-only gate).
 *
 * Mounts the REAL production CreatorComposer with a real SessionStore,
 * synthetic normalized JPEGs (decoded via createImageBitmap — no camera,
 * no network, no provider), a canvas word-wrap measurer gated on loaded
 * bundled fonts, and a synthetic confirmed recipient. Asserts the visible
 * preview, pager, slot selection, zoom/pan repaint, reset, freeze, edit
 * invalidation, music honesty, and lock/unmount cleanup. Reports
 * `window.__creatorComposer = { assertions, failures }` for the node
 * runner. Not camera/OAuth/Android/iOS acceptance.
 */
import { createRoot, type Root } from 'react-dom/client'
import { useState } from 'react'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { previewCandidates } from '../../src/collage/creatorPreview'
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

function App(props: {
  photos: CreatorComposerPhoto[]
  session: SessionStore
  measurer: TextMeasurer
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
        note="Probe note text."
        musicSelected={musicSelected}
        styleId="press"
        recipient={{ userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16), handle: 'ada' }}
        ownerId={ACCOUNT}
        session={props.session}
        lang={lang === 'ru' ? 'ru' : lang === 'uk' ? 'uk' : 'en'}
        measurer={props.measurer}
      />
    </div>
  )
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'composer-status'
  status.textContent = 'starting';
  (window as unknown as { __creatorComposer?: unknown }).__creatorComposer = { assertions, failures }
  let root: Root | null = null
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
    const photos: CreatorComposerPhoto[] = await Promise.all(
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
      handle: 'ada',
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    ;(window as unknown as { __session?: SessionStore }).__session = session
    document.body.innerHTML = '<div id="root"></div>'
    document.body.appendChild(status)
    root = createRoot(query('#root'))
    root.render(<App photos={photos} session={session} measurer={canvasMeasurer()} />)
    await runChecks(photos, session)
    status.textContent = 'done'
  } catch (error) {
    fail('harness setup', String(error))
    status.textContent = 'failed'
  } finally {
    if (root !== null) {
      const handle = root
      await waitFor(() => {
        handle.unmount()
        return document.querySelector('[data-testid="creator-canvas"]') === null
      }, 'unmount cleanup')
      assert('unmount drops the composer without errors', document.querySelector('[data-testid="creator-canvas"]') === null)
    }
  }
}

async function runChecks(photos: CreatorComposerPhoto[], session: SessionStore): Promise<void> {
  await waitFor(() => canvasState() === 'ready', 'initial paint');
  (() => {
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    assert('composer mounted with preview canvas', canvas.width === 360 && canvas.height === 640)
    assert('preview painted ready', canvasState() === 'ready')
    const first = textOf('creator-branch')
    ;(window as unknown as { __firstBranch?: string }).__firstBranch = first
  })();
  click('creator-next')
  await waitFor(() => {
    try {
      return textOf('creator-branch') !== (window as unknown as { __firstBranch?: string }).__firstBranch
    } catch {
      return false
    }
  }, 'pager next')
  assert('pager next changes branch', true)
  assert('pager count advances', /\b2\b/.test(textOf('creator-count')), textOf('creator-count'))
  click('creator-prev')
  await waitFor(() => {
    try {
      return textOf('creator-branch') === (window as unknown as { __firstBranch?: string }).__firstBranch
    } catch {
      return false
    }
  }, 'pager prev')
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
  await (async () => {
    const before = textOf('creator-transform-readout')
    click('creator-zoom-in')
    await waitFor(() => textOf('creator-transform-readout') !== before, 'zoom repaint')
    await waitFor(() => canvasState() === 'ready', 'zoom paint')
    assert('zoom changes transform and repaints', true)
    click('creator-pan-right')
    const panned = textOf('creator-transform-readout')
    assert('pan changes the source window', panned !== before, panned)
    click('creator-reset')
    await waitFor(() => textOf('creator-transform-readout') === before, 'reset repaint')
    await waitFor(() => canvasState() === 'ready', 'reset paint')
    assert('reset restores the starting crop', true)
  })();
  (() => {
    const views = previewCandidates({
      id: 'browser',
      sender: 'ada',
      photos: photos.map((photo) => ({ contentId: photo.contentId, width: photo.width, height: photo.height, contentHash: photo.contentHash })),
      note: 'Probe note text.',
    })
    const tile = views[0].slots[0]
    const canvas = query<HTMLCanvasElement>('[data-testid="creator-canvas"]')
    const left = pixelAt(canvas, tile.destination.x + tile.destination.width * 0.25, tile.destination.y + tile.destination.height * 0.5)
    const right = pixelAt(canvas, tile.destination.x + tile.destination.width * 0.75, tile.destination.y + tile.destination.height * 0.5)
    assert('two-tone source spans the cell (red left)', left[0] > 120 && left[2] < 120, left.join(','))
    assert('two-tone source spans the cell (blue right)', right[2] > 100 && right[0] < 140, right.join(','))
  })();
  await (async () => {
    click('creator-freeze')
    await waitFor(() => {
      try {
        return textOf('creator-frozen').length > 0
      } catch {
        return false
      }
    }, 'freeze snapshot')
    assert('explicit freeze snapshots the selection', textOf('creator-frozen').length > 0)
    click('creator-zoom-in')
    await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'edit invalidation')
    assert('crop edit invalidates the freeze', true)
  })();
  click('page-music-toggle')
  await waitFor(() => {
    try {
      return textOf('creator-freeze-blocked').length > 10
    } catch {
      return false
    }
  }, 'music notice')
  assert('music shows the honest unsupported notice', textOf('creator-freeze-blocked').length > 10)
  assert('freeze is disabled with music selected', query<HTMLButtonElement>('[data-testid="creator-freeze"]').disabled)
  click('page-music-toggle')
  await waitFor(() => document.querySelector('[data-testid="creator-freeze-blocked"]') === null, 'music off')
  await (async () => {
    session.lock()
    await waitFor(() => {
      try {
        return textOf('creator-locked').length > 0
      } catch {
        return false
      }
    }, 'lock teardown')
    assert('lock clears the preview with an honest notice', true)
    session.unlock()
    await waitFor(() => canvasState() === 'ready', 'post-unlock repaint')
    assert('unlock restores the live preview', canvasState() === 'ready')
  })()
}

void main()
