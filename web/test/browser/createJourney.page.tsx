/**
 * T241 connected actual-Create journey (Chromium-only gate).
 *
 * Mounts the REAL production `Create` component (App.tsx) with a real
 * `SessionStore`, `RecipientDraft` (synthetic confirmed recipient),
 * `PhotoDraft`, `NoteDraft`, `MusicDraft` and the production photo
 * normalizer — then picks real generated JPEG Files through the ACTUAL
 * file input, so photos flow normalization → fenced decode → the mounted
 * `CreatorComposer`. No prefilled composer props, no network/provider/
 * camera; synthetic auth/directory ports are explicitly NOT provider
 * evidence.
 */
import { createRoot, type Root } from 'react-dom/client'
import { Create } from '../../src/ui/App'
import { NoteDraft } from '../../src/create/note'
import { MusicDraft } from '../../src/create/music'
import { PhotoDraft } from '../../src/create/photos'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import { browserImageDecode, type CreatorImageDecode } from '../../src/create/creatorImages'
import { catalog, type MessageKey, type Language } from '../../src/i18n/catalog'
import type { ApiTransport } from '../../src/auth/http'
import type { CameraPort } from '../../src/scan/camera'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const frozenLog: Array<{ frozen: boolean }> = []
const notices: string[] = []

function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'

function snapshot(): RecipientSnapshot {
  return {
    userId: ACCOUNT, handle: 'ada', keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocolVersion: 1,
    encryptionPublicKeyset: 'x'.repeat(10), signingPublicKeyset: 'y'.repeat(10),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

async function gradientJpeg(width: number, height: number, tone: number): Promise<File> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const image = context.createImageData(width, height)
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const at = (y * width + x) * 4
      image.data[at] = (x / width) * 200 + 20
      image.data[at + 1] = tone
      image.data[at + 2] = (y / height) * 200 + 20
      image.data[at + 3] = 255
    }
  }
  context.putImageData(image, 0, 0)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9))
  if (!blob) throw new Error('encode failed')
  return new File([blob], `photo-${tone}.jpg`, { type: 'image/jpeg' })
}

function canvasState(): string | null {
  const canvas = document.querySelector('[data-testid="creator-canvas"]')
  if (!(canvas instanceof HTMLCanvasElement)) return null
  return canvas.dataset['painted'] ?? 'missing'
}
function readout(): number[] {
  return [...__textOf('creator-transform-readout').matchAll(/-?\d+(?:\.\d+)?/g)].map((match) => Number(match[0]))
}
function sameReadout(actual: number[], expected: number[]): boolean {
  return actual.length === expected.length && actual.every((value, index) => Math.abs(value - expected[index]) <= 1e-9)
}
function __textOf(testid: string): string {
  return (document.querySelector(`[data-testid="${testid}"]`)?.textContent) ?? ''
}
async function waitFor(fn: () => boolean, label: string, timeoutMs = 30000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    let done = false
    try { done = fn() } catch { done = false }
    if (done) return
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${label}`)
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}

interface Harness {
  create: () => void
  notes: NoteDraft
  music: MusicDraft
  photos: PhotoDraft
  recipient: RecipientDraft
  session: SessionStore
  pick: (files: File[]) => void
  setNote: (text: string) => void
  setMusic: (selected: boolean) => void
  trim: () => void
  move: () => void
  restartRecipient: () => void
  holdDecode: () => void
  releaseDecode: () => void
}

function harness(lang: Language): Harness {
  const store = new SessionStore(lang)
  store.signIn({
    accountId: ACCOUNT, bundleId: BUNDLE, handle: 'sergio',
    accessToken: 'access-token-value', refreshToken: 'refresh-token-value',
    accessExpiresAt: '2026-10-17T00:00:00Z', refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  store.unlock()
  const recipient = new RecipientDraft()
  recipient.attach(store)
  const ticket = recipient.nextTicket(ACCOUNT)
  recipient.offer(ticket, ACCOUNT, snapshot())
  recipient.confirm()
  const note = new NoteDraft()
  const photos = new PhotoDraft()
  photos.attach(store)
  const music = new MusicDraft()
  const t = (key: MessageKey): string => catalog[lang][key]

  const transport: ApiTransport = { request: async () => { throw new Error('no network in this fixture') } }
  const camera: CameraPort = { open: async () => { throw new Error('no camera in this fixture') } }

  let gate: Promise<void> | null = null
  const real = browserImageDecode()
  const imageDecode: CreatorImageDecode = async (bytes) => { if (gate) await gate; return real(bytes) }

  const rootRef: { root: Root | null } = { root: null }
  const render = () => {
    if (rootRef.root === null) {
      const host = document.createElement('div')
      host.setAttribute('data-testid', 'create-host')
      document.body.appendChild(host)
      rootRef.root = createRoot(host)
    }
    rootRef.root.render(
      <Create
        t={t}
        store={store}
        transport={transport}
        recipient={recipient}
        camera={camera}
        note={note}
        photos={photos}
        music={music}
        onNotice={(notice) => { notices.push(notice) }}
        imageDecode={imageDecode}
      />,
    )
  }
  // Music selection fixture: one synthetic hit selected directly on the draft.
  const selectMusic = (selected: boolean) => {
    if (selected) {
      const ticketId = music.nextTicket()
      music.offer(ticketId, [{ id: '0198f0a0-0000-7000-8000-00000000c001', title: 'Track', artists: ['Artist'], version: null, release: null, year: null, durationMs: null, artworkAvailable: false, recordingMbid: null }])
      music.select('0198f0a0-0000-7000-8000-00000000c001')
    } else {
      music.remove()
    }
    render()
  }
  return {
    create: render,
    notes: note,
    music,
    photos,
    recipient,
    session: store,
    pick: (files) => {
      const input = document.querySelector('.photo-draft input[type="file"]') as HTMLInputElement | null
      if (!input) throw new Error('photo input missing')
      const transfer = new DataTransfer()
      for (const file of files) transfer.items.add(file)
      input.files = transfer.files
      input.dispatchEvent(new Event('change', { bubbles: true }))
    },
    setNote: (text) => { note.edit(text); render() },
    setMusic: selectMusic,
    trim: () => { const first = photos.photos()[0]; if (first) photos.remove(first.id); render() },
    move: () => { const second = photos.photos()[1]; if (second) photos.move(second.id, -1); render() },
    restartRecipient: () => { recipient.restart(); render() },
    holdDecode: () => { gate = new Promise<void>((resolve) => { gateRelease = resolve }) },
    releaseDecode: () => { gate = null; gateRelease?.() },
  } as Harness
}

let gateRelease: (() => void) | null = null

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'journey-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  window.addEventListener('error', (event) => {
    fail('window error', String(event.error ?? event.message))
    status.textContent = 'failed'
  })
  window.addEventListener('unhandledrejection', (event) => {
    fail('unhandled rejection', String(event.reason))
    status.textContent = 'failed'
  })
  ;(window as unknown as { __createJourney?: unknown }).__createJourney = { assertions, failures, frozen: frozenLog }
  const realClose = ImageBitmap.prototype.close
  ;(window as unknown as { __bitmapCloses?: number }).__bitmapCloses = 0
  ImageBitmap.prototype.close = function (this: ImageBitmap): void {
    ;(window as unknown as { __bitmapCloses?: number }).__bitmapCloses =
      (((window as unknown as { __bitmapCloses?: number }).__bitmapCloses) ?? 0) + 1
    realClose.call(this)
  }
  const langParam = new URLSearchParams(window.location.search).get('lang')
  const lang: Language = langParam === 'ru' ? 'ru' : langParam === 'uk' ? 'uk' : 'en'
  try {
    const files = [
      await gradientJpeg(800, 600, 40),
      await gradientJpeg(600, 800, 90),
      await gradientJpeg(1000, 1000, 150),
    ]
    const h = harness(lang)
    h.create()
    await runChecks(h, files, status)
    status.textContent = 'capture-ready'
    await waitFor(() => (window as unknown as { __createRelease?: boolean }).__createRelease === true, 'runner release')
    status.textContent = 'done'
  } catch (error) {
    fail('journey harness', String(error))
    status.textContent = 'failed'
  }
}

async function runChecks(h: Harness, files: File[], status: HTMLElement): Promise<void> {
  void status
  // 0. The real Create component commits asynchronously; wait for the
  //    confirmed recipient surface before using the actual file input.
  await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'create confirmed flow')
  // 1. Pick real JPEGs through the ACTUAL file input.
  h.pick(files)
  await waitFor(() => h.photos.accepted()?.length === 3, 'three normalized photos')
  assert('picker normalized 3 photos', h.photos.accepted()?.length === 3)
  await waitFor(() => canvasState() === 'ready', 'connected composer paints')
  assert('real Create mounted the composer and painted a candidate', canvasState() === 'ready')
  assert('composer is in the Create document', document.querySelector('[data-testid="creator-composer"]') !== null)

  // 2. Pager + crop affect the real window.
  __click('creator-next')
  await waitFor(() => __textOf('creator-count').length > 0, 'pager')
  __click('creator-slot-0')
  __click('creator-zoom-in')
  await waitFor(() => !sameReadout(readout(), [1, 0, 0]), 'zoom applies')
  await waitFor(() => canvasState() === 'ready', 'zoom repaint')
  assert('crop zoom changes the real transform', !sameReadout(readout(), [1, 0, 0]))
  __click('creator-reset')
  await waitFor(() => sameReadout(readout(), [1, 0, 0]), 'reset')

  // 3. Local freeze + parent callback.
  await waitFor(() => !__disabled('creator-freeze'), 'freeze armed')
  __click('creator-freeze')
  await waitFor(() => __textOf('creator-frozen').length > 0, 'freeze')
  assert('explicit freeze produces a local selection', __textOf('creator-frozen').length > 0)

  // 4. Note edit invalidates.
  h.setNote('A remembered afternoon beside the sea, a long walk home, and all the little things we wanted to keep for later.')
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'note invalidation')
  assert('note edit invalidates the held selection', document.querySelector('[data-testid="creator-frozen"]') === null)
  await waitFor(() => canvasState() === 'ready', 'note repaint')
  assert('multi-line note still previews', canvasState() === 'ready')
  h.setNote('Probe note text.')

  // 5. Remove to 2 -> composer unavailable; restore -> composer returns.
  h.trim()
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'two-photo composer unmount')
  assert('two photos remove the composer honestly', document.querySelector('[data-testid="creator-canvas"]') === null)
  h.pick([files[2] as File])
  await waitFor(() => canvasState() === 'ready', 'restored composer paint')
  assert('re-adding to 3 restores the composer', canvasState() === 'ready')

  // 6. Music selected disables freeze (unsupported family) and drops any hold.
  await waitFor(() => !__disabled('creator-freeze'), 'freeze armed again')
  __click('creator-freeze')
  await waitFor(() => __textOf('creator-frozen').length > 0, 'freeze before music')
  assert('freeze before music', __textOf('creator-frozen').length > 0)
  h.setMusic(true)
  await waitFor(() => __disabled('creator-freeze'), 'music disables freeze')
  assert('music selection disables the unsupported-family freeze', __disabled('creator-freeze'))
  await waitFor(() => document.querySelector('[data-testid="creator-frozen"]') === null, 'music drops hold')
  assert('music selection drops the held selection', document.querySelector('[data-testid="creator-frozen"]') === null)
  h.setMusic(false)
  await waitFor(() => !__disabled('creator-freeze'), 'music off re-arms')

  // 7. Reorder keeps a ready composer.
  h.move()
  await waitFor(() => canvasState() === 'ready', 'reorder repaint')
  assert('reorder keeps a ready composer', canvasState() === 'ready')

  // 8. Held decode across a lock: stale completion closed, nothing leaked.
  //    (Lock intentionally drops the confirmed recipient, so the composer
  //    stays detached — the preview is not resurrected from stale state.)
  const closedBefore = closedBitmaps()
  h.setMusic(false)
  h.setNote('Probe note text.')
  h.trim()
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'held trim unmounts')
  h.holdDecode()
  h.pick([files[0] as File])
  await new Promise((resolve) => setTimeout(resolve, 200))
  assert('held decode never publishes a composer before release', document.querySelector('[data-testid="creator-canvas"]') === null)
  h.session.lock()
  await new Promise((resolve) => setTimeout(resolve, 100))
  h.releaseDecode()
  await new Promise((resolve) => setTimeout(resolve, 250))
  assert('held decode closed its owned bitmaps after the stale lock', closedBitmaps() > closedBefore)
  assert('lock keeps the composer detached (no stale publish)', document.querySelector('[data-testid="creator-canvas"]') === null)

  // 9. Recovery: lock dropped the recipient + photo draft, so a fresh
  //    confirm + pick restores the connected composer (real draft kept).
  h.session.unlock()
  const ticket = h.recipient.nextTicket(ACCOUNT)
  h.recipient.offer(ticket, ACCOUNT, snapshot())
  h.recipient.confirm()
  h.create()
  await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'recovered create flow')
  h.pick(files)
  await waitFor(() => canvasState() === 'ready', 'recovered composer paint')
  assert('re-confirmed recipient + fresh photos restore the connected composer', canvasState() === 'ready')
  assert('recovered composer still renders a candidate', __textOf('creator-branch').length > 0)
}

function __click(testid: string): void {
  const node = document.querySelector(`[data-testid="${testid}"]`) as HTMLButtonElement | null
  if (!node || node.disabled) throw new Error(`${testid} not clickable`)
  node.click()
}
function __disabled(testid: string): boolean {
  const node = document.querySelector(`[data-testid="${testid}"]`) as HTMLButtonElement | null
  return node === null || node.disabled
}
function closedBitmaps(): number {
  return (window as unknown as { __bitmapCloses?: number }).__bitmapCloses ?? 0
}

void main()
