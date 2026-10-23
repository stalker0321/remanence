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
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const frozenLog: Array<{ frozen: boolean }> = []
const notices: string[] = []

interface FontFact { request: string; family: string | null; status: string | null; faces: number }
interface LayoutFact {
  tokenColumn: string
  tokenLilac: string
  thumbHeight: number | null
  scrollWidth: number
  innerWidth: number
  composerWidth: number | null
  freezeRight: number | null
}
const fontFacts: FontFact[] = []
const layoutFacts: LayoutFact[] = []
const FACES = [
  { request: '400 16px StudySans', family: 'StudySans' },
  { request: '400 20px StudySerif', family: 'StudySerif' },
] as const

// Real bundle-font proof: production `tokens.css` registers the StudySans/
// StudySerif `@font-face` rules; the runner serves the actual `.otf` bytes.
// A NON-EMPTY `document.fonts.load` result with `status === 'loaded'` proves a
// real face was registered and fetched — `document.fonts.check`/`ctx.font`
// alone can pass against an unregistered fallback family.
async function assertRealFonts(): Promise<void> {
  for (const { request, family } of FACES) {
    const faces = await document.fonts.load(request)
    await Promise.all(faces.map((face) => face.load()))
    const fact: FontFact = {
      request,
      family: faces[0]?.family ?? null,
      status: faces[0]?.status ?? null,
      faces: faces.length,
    }
    fontFacts.push(fact)
    assert(
      `production ${family} face is registered and loads real bytes`,
      faces.length > 0 && faces.every((face) => face.family.includes(family)),
      JSON.stringify(fact),
    )
    assert(
      `loaded ${family} faces report status=loaded`,
      faces.length > 0 && faces.every((face) => face.status === 'loaded'),
      JSON.stringify(fact),
    )
  }
  assert('font face set reports the registered families loaded', document.fonts.check('400 16px StudySans') && document.fonts.check('400 20px StudySerif'))
}

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
  signIn: () => void
  unmount: () => void
}

const SIGN_IN = {
  accountId: ACCOUNT, bundleId: BUNDLE, handle: 'sergio',
  accessToken: 'access-token-value', refreshToken: 'refresh-token-value',
  accessExpiresAt: '2026-10-17T00:00:00Z', refreshExpiresAt: '2026-11-17T00:00:00Z',
}

function harness(lang: Language): Harness {
  const store = new SessionStore(lang)
  store.signIn(SIGN_IN)
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
  const imageDecode: CreatorImageDecode = async (bytes) => {
    ;(window as unknown as { __decodeCount?: number }).__decodeCount =
      ((window as unknown as { __decodeCount?: number }).__decodeCount ?? 0) + 1
    if (gate) await gate
    return real(bytes)
  }

  const rootRef: { root: Root | null } = { root: null }
  const render = () => {
    if (rootRef.root === null) {
      const host = document.createElement('div')
      host.setAttribute('data-testid', 'create-host')
      // Production `App` renders views inside `<main className="shell">`; mirror
      // that column so the fixture exercises the real production layout.
      host.className = 'shell'
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
    signIn: () => { store.signIn(SIGN_IN); store.unlock() },
    unmount: () => {
      rootRef.root?.unmount()
      rootRef.root = null
      for (const host of [...document.querySelectorAll('[data-testid="create-host"]')]) host.remove()
    },
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
  ;(window as unknown as { __createJourney?: unknown }).__createJourney = { assertions, failures, frozen: frozenLog, fonts: fontFacts, layout: layoutFacts }
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
    await assertRealFonts()
    const files = [
      await gradientJpeg(800, 600, 40),
      await gradientJpeg(600, 800, 90),
      await gradientJpeg(1000, 1000, 150),
    ]
    const spare = await gradientJpeg(700, 500, 210)
    const h = harness(lang)
    h.create()
    await runChecks(h, files, spare, status)
    status.textContent = 'capture-ready'
    await waitFor(() => (window as unknown as { __createRelease?: boolean }).__createRelease === true, 'runner release')
    await runTeardown(h)
    status.textContent = 'done'
  } catch (error) {
    fail('journey harness', String(error))
    status.textContent = 'failed'
  }
}

async function runChecks(h: Harness, files: File[], spare: File, status: HTMLElement): Promise<void> {
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
  // A DISTINCT third file keeps three distinct cached handles for the
  // teardown counts below (re-picking an existing file would duplicate one).
  h.pick([spare])
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

  // 8. Prompt owned teardown: the PUBLISHED decoded handles are closed on
  //    lock / recipient loss / account boundary; recovery re-decodes from
  //    the current draft, never reusing pre-lock plaintext.
  const confirmRecipient = (): void => {
    const ticket = h.recipient.nextTicket(ACCOUNT)
    h.recipient.offer(ticket, ACCOUNT, snapshot())
    h.recipient.confirm()
    h.create()
  }
  const pickThree = async (): Promise<void> => {
    await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'create flow')
    h.pick(files)
    await waitFor(() => canvasState() === 'ready', 'composer paint')
  }
  h.setMusic(false)
  h.setNote('Probe note text.')

  const decodesBefore = decodeCount()
  let closesBefore = closedBitmaps()
  h.session.lock()
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'lock detaches composer')
  await new Promise((resolve) => setTimeout(resolve, 150))
  assert('lock closes every published decoded handle', closedBitmaps() - closesBefore >= 3, `${closedBitmaps()} - ${closesBefore}`)

  h.session.unlock()
  confirmRecipient()
  await pickThree()
  assert('recovered composer paints after lock teardown', canvasState() === 'ready')
  assert('recovery re-decodes instead of reusing pre-lock plaintext', decodeCount() > decodesBefore, `${decodeCount()} vs ${decodesBefore}`)

  // Recipient loss releases the published decoded handles too.
  closesBefore = closedBitmaps()
  h.recipient.restart()
  h.create()
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'recipient loss detaches composer')
  await new Promise((resolve) => setTimeout(resolve, 150))
  assert('recipient loss closes the published decoded handles', closedBitmaps() - closesBefore >= 3, `${closedBitmaps()} - ${closesBefore}`)

  // Re-confirm restores the composer from the retained photo draft (no
  // re-pick), then the account boundary (signOut) releases it.
  confirmRecipient()
  await waitFor(() => canvasState() === 'ready', 're-confirmed composer paint')
  closesBefore = closedBitmaps()
  h.session.signOut()
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]') === null, 'signOut detaches composer')
  await new Promise((resolve) => setTimeout(resolve, 150))
  assert('account boundary closes the published decoded handles', closedBitmaps() - closesBefore >= 3, `${closedBitmaps()} - ${closesBefore}`)

  // 9. Held decode across a lock: the LATE owned success is closed and the
  //    composer is not republished for the stale context.
  h.signIn()
  confirmRecipient()
  await pickThree()
  const heldCloses = closedBitmaps()
  h.holdDecode()
  h.pick([spare])
  await new Promise((resolve) => setTimeout(resolve, 200))
  h.session.lock()
  await new Promise((resolve) => setTimeout(resolve, 100))
  h.releaseDecode()
  await new Promise((resolve) => setTimeout(resolve, 250))
  assert('held decode closed its late owned handle after the stale lock', closedBitmaps() > heldCloses, `${closedBitmaps()} vs ${heldCloses}`)
  assert('stale context keeps the composer detached', document.querySelector('[data-testid="creator-canvas"]') === null)

  // 10. Leave a mounted, ready composer for the capture-ready screenshot
  //     (unmount teardown runs after the runner releases, below).
  h.signIn()
  confirmRecipient()
  await pickThree()
  assert('capture-ready frame shows the mounted connected composer', canvasState() === 'ready')

  // Content and composition are distinct visible workspaces. Inspect each
  // through its real navigation, not zero-sized nodes in a hidden panel.
  h.setNote('Probe note text.')
  const steps = () => [...document.querySelectorAll<HTMLButtonElement>('.workspace-nav button')]
  steps()[0]!.click()
  await waitFor(() => document.querySelector<HTMLElement>('.content-workspace')?.hidden === false, 'visible content')
  const thumbHeight = document.querySelector('.photo-draft img')?.getBoundingClientRect().height ?? null
  await waitFor(() => steps()[1]?.disabled === false, 'composition step ready')
  steps()[1]!.click()
  await waitFor(() => document.querySelector<HTMLElement>('.composition-workspace')?.hidden === false, 'visible composition')

  // 11. Styled/responsive acceptance at the intended viewport: production
  //     tokens.css is applied, photo controls are constrained, the connected
  //     composer fits, and the page has no horizontal overflow.
  const rootStyle = getComputedStyle(document.documentElement)
  // Bound against the real root size; thumbnails are now 7rem.
  const rootFont = Number.parseFloat(rootStyle.fontSize) || 16
  const composer = document.querySelector('[data-testid="creator-composer"]') as HTMLElement | null
  const freeze = document.querySelector('[data-testid="creator-freeze"]') as HTMLElement | null
  const freezeRect = freeze?.getBoundingClientRect() ?? null
  const layoutFact: LayoutFact = {
    tokenColumn: rootStyle.getPropertyValue('--column').trim(),
    tokenLilac: rootStyle.getPropertyValue('--lilac').trim(),
    thumbHeight: thumbHeight === null ? null : Math.round(thumbHeight),
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    composerWidth: composer ? Math.round(composer.getBoundingClientRect().width) : null,
    freezeRight: freezeRect ? Math.round(freezeRect.right) : null,
  }
  layoutFacts.push(layoutFact)
  assert('production tokens.css is applied (design token present)', layoutFact.tokenColumn === '70rem' && layoutFact.tokenLilac === '#dcd9ee', JSON.stringify(layoutFact))
  assert('photo controls are constrained by production styles', layoutFact.thumbHeight !== null && layoutFact.thumbHeight > 0 && layoutFact.thumbHeight <= 7 * rootFont + 1, JSON.stringify(layoutFact))
  assert('no horizontal overflow at the intended viewport', layoutFact.scrollWidth <= layoutFact.innerWidth + 1, JSON.stringify(layoutFact))
  assert('connected composer fits the intended viewport width', layoutFact.composerWidth !== null && layoutFact.composerWidth <= layoutFact.innerWidth + 1, JSON.stringify(layoutFact))
  assert('critical composer controls are reachable in the viewport', layoutFact.freezeRight !== null && layoutFact.freezeRight > 0 && layoutFact.freezeRight <= layoutFact.innerWidth + 1, JSON.stringify(layoutFact))
}

async function runTeardown(h: Harness): Promise<void> {
  const closesBefore = closedBitmaps()
  h.unmount()
  await new Promise((resolve) => setTimeout(resolve, 150))
  assert('unmount releases the connected composer', document.querySelector('[data-testid="creator-canvas"]') === null)
  assert('unmount closes the published decoded handles', closedBitmaps() - closesBefore >= 3, `${closedBitmaps()} - ${closesBefore}`)
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
function decodeCount(): number {
  return (window as unknown as { __decodeCount?: number }).__decodeCount ?? 0
}

void main()
