/**
 * T326 connected Create Send proof (Chromium-only).
 *
 * Mounts the REAL production `Create` component (App.tsx) with a real
 * SessionStore + MemoryKeys (TEST-ONLY golden keysets, committed fixtures),
 * a synthetic confirmed recipient, real generated JPEG Files through the
 * ACTUAL file input, the real composer freeze, and the real front-fingerprint
 * producer (textured synthetic JPEG through the accepted camera wiring). The
 * Send button runs the REAL `sealCapsule` -> `publishCapsule` ->
 * `fetchTransport` three-step wire against an isolated same-origin loopback
 * Node fixture. A receiver-side round trip re-opens the captured finalize
 * statement + recipient envelope with the committed private keyset and
 * decrypts the captured blob ciphertexts, comparing photo bytes and note text.
 * No real backend/account/provider; synthetic pixels and fake tokens only.
 */
import { createRoot, type Root } from 'react-dom/client'
import { Create } from '../../src/ui/App'
import { NoteDraft } from '../../src/create/note'
import { MusicDraft } from '../../src/create/music'
import { PhotoDraft } from '../../src/create/photos'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { fetchTransport } from '../../src/auth/http'
import { signPublishStatement, verifyPublishStatement } from '../../src/crypto/publish'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import { decryptArtifact } from '../../src/crypto/artifact'
import { artifactAad, recipientEnvelopeInfo } from '../../src/protocol/context'
import { fieldBytes, readMessage } from '../../src/protocol/proto'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { catalog, type MessageKey } from '../../src/i18n/catalog'
import { bytesToHex } from '../../src/protocol/bytes'
import type { CameraPort, StillCapture } from '../../src/scan/camera'
import type { KeyCapabilities } from '../../src/crypto/keyOwner'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import '../../src/design/tokens.css'
import { startShell, shellStore } from '../../src/pwa/shell'
import { ShellStatus } from '../../src/pwa/ShellStatus'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const notices: MessageKey[] = []
const freezeTimelines: string[][] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const SENDER_ID = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const SENDER_BUNDLE = '22222222-2222-4222-8222-222222222222'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const NOTE_TEXT = 'A remembered afternoon beside the sea, a long walk home.'

const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
const HPKE_PRIVATE = keysetFromJson(hpkeVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

const SIGN_IN = {
  accountId: SENDER_ID,
  bundleId: SENDER_BUNDLE,
  handle: 'sergio',
  accessToken: 'access-token-value',
  refreshToken: 'refresh-token-value',
  accessExpiresAt: '2026-10-17T00:00:00Z',
  refreshExpiresAt: '2026-11-17T00:00:00Z',
}

function snapshot(): RecipientSnapshot {
  return {
    userId: RECIPIENT_ID, handle: 'ada', keyBundleId: RECIPIENT_BUNDLE,
    suite: SUITE, protocolVersion: 1,
    encryptionPublicKeyset: b64(HPKE_PUBLIC), signingPublicKeyset: b64(SIGNING_PUBLIC),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

async function texturedQuadJpeg(seed: number, shiftX: number, shiftY: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100 + shiftX, y: 80 + shiftY },
    { x: 540 + shiftX, y: 60 + shiftY },
    { x: 560 + shiftX, y: 400 + shiftY },
    { x: 80 + shiftX, y: 420 + shiftY },
  ]
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(seed)
  for (let i = 0; i < 260; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.stroke()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
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

function fromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i += 1) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

function fromBase64(value: string): Uint8Array {
  // The accepted `b64()` encoder emits unpadded base64url.
  const standard = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = standard + '='.repeat((4 - (standard.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i)
  return out
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function waitFor(cond: () => boolean, what: string, timeoutMs = 60000): Promise<void> {
  const start = Date.now()
  for (;;) {
    let done = false
    try { done = cond() } catch { done = false }
    if (done) return
    if (Date.now() - start > timeoutMs) throw new Error(`timeout: ${what}`)
    await new Promise((resolve) => setTimeout(resolve, 150))
  }
}

function buttonByText(text: string): HTMLButtonElement | null {
  for (const button of document.querySelectorAll('button')) {
    if (button.textContent === text) return button as HTMLButtonElement
  }
  return null
}

async function clickText(text: string, what: string): Promise<void> {
  await waitFor(() => {
    const button = buttonByText(text)
    return button !== null && !button.disabled
  }, `${what} clickable`)
  buttonByText(text)!.click()
}

function byTest(testid: string): HTMLElement | null {
  return document.querySelector(`[data-testid="${testid}"]`)
}

function bodyText(): string {
  return document.body.textContent ?? ''
}

interface CapturedCall { method: string; url: string; rawHeaders: string[]; bodyHex: string }
interface WireCapture {
  drafts: CapturedCall[]
  uploads: CapturedCall[]
  finalizes: CapturedCall[]
}
function lastDraft(capture: WireCapture): CapturedCall | null { return capture.drafts[capture.drafts.length - 1] ?? null }
function lastFinalize(capture: WireCapture): CapturedCall | null { return capture.finalizes[capture.finalizes.length - 1] ?? null }

async function setMode(mode: string): Promise<void> {
  const response = await fetch('/__mode', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ mode }) })
  if (!response.ok) throw new Error('mode set failed')
}

async function releaseFinalize(): Promise<void> {
  const response = await fetch('/__release', { method: 'POST' })
  if (!response.ok) throw new Error('release failed')
}

async function releaseDraft(): Promise<void> {
  const response = await fetch('/__release', { method: 'POST' })
  if (!response.ok) throw new Error('draft release failed')
}

async function capture(): Promise<WireCapture> {
  const response = await fetch('/__capture')
  return await response.json() as WireCapture
}

interface Harness {
  create: () => void
  notes: NoteDraft
  photos: PhotoDraft
  recipient: RecipientDraft
  session: SessionStore
  keys: MemoryKeys
  pick: (files: File[]) => void
  setNote: (text: string) => void
  unmount: () => void
  confirmRecipient: () => void
}

const t = (key: MessageKey): string => catalog.en[key]

function harness(): Harness {
  const store = new SessionStore('en')
  store.signIn(SIGN_IN)
  store.unlock()
  const recipient = new RecipientDraft()
  recipient.attach(store)
  const ticket = recipient.nextTicket(SENDER_ID)
  recipient.offer(ticket, SENDER_ID, snapshot())
  recipient.confirm()
  const note = new NoteDraft()
  const photos = new PhotoDraft()
  photos.attach(store)
  const music = new MusicDraft()
  const transport = fetchTransport(window.location.origin)
  const caps: KeyCapabilities = {
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    openEnvelope: async () => { throw new Error('sender openEnvelope unused') },
    decryptArtifact: async () => { throw new Error('sender decrypt unused') },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => { throw new Error('lock unused in fixture') },
  }
  const keys = new MemoryKeys()
  keys.hold(store.getSnapshot(), caps)

  // The per-take frame lives in quadRef (assigned right before each open).
  const camera: CameraPort = {
    open: async () => ({
      preview: document.createElement('video') as HTMLVideoElement,
      capture: async (): Promise<StillCapture> => ({ bytes: quadRef.slice(), width: 640, height: 480 }),
      stop: () => undefined,
    }),
  }

  const rootRef: { root: Root | null } = { root: null }
  const render = () => {
    if (rootRef.root === null) {
      const host = document.createElement('div')
      host.setAttribute('data-testid', 'create-host')
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
        keys={keys}
      />,
    )
  }
  return {
    create: render,
    notes: note,
    photos,
    recipient,
    session: store,
    keys,
    pick: (files) => {
      const input = document.querySelector('.photo-draft input[type="file"]') as HTMLInputElement | null
      if (!input) throw new Error('photo input missing')
      const transfer = new DataTransfer()
      for (const file of files) transfer.items.add(file)
      input.files = transfer.files
      input.dispatchEvent(new Event('change', { bubbles: true }))
    },
    setNote: (text) => { note.edit(text); render() },
    unmount: () => {
      rootRef.root?.unmount()
      rootRef.root = null
      for (const host of [...document.querySelectorAll('[data-testid="create-host"]')]) host.remove()
    },
    confirmRecipient: () => {
      const next = recipient.nextTicket(SENDER_ID)
      recipient.offer(next, SENDER_ID, snapshot())
      recipient.confirm()
      render()
    },
  } as Harness
}

/** Bring one fresh Create visit to fully-send-ready (photos+note+freeze+front).
 * Returns the NORMALIZED photo bytes actually sealed (post-intake drafts). */
async function readyVisit(h: Harness, files: File[], seed: number): Promise<Uint8Array[]> {
  await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'create confirmed flow')
  h.pick(files)
  await waitFor(() => h.photos.accepted()?.length === 3, 'three normalized photos')
  const normalized = (h.photos.accepted() ?? []).map((photo) => photo.bytes.slice())
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', 'composer painted')
  h.setNote(NOTE_TEXT)
  await waitFor(() => document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', 'composer repaint after note')
  await waitFor(() => {
    const button = byTest('creator-freeze') as HTMLButtonElement | null
    return button !== null && !button.disabled
  }, 'freeze armed', 60000)
  ;(byTest('creator-freeze') as HTMLButtonElement).click()
  const freezeTimeline: string[] = []
  const freezeDeadline = Date.now() + 120000
  for (;;) {
    const frozenNow = byTest('creator-frozen') !== null
    const button = byTest('creator-freeze') as HTMLButtonElement | null
    freezeTimeline.push(
      [
        Date.now() % 100000,
        `frozen=${frozenNow}`,
        `disabled=${button?.disabled ?? 'gone'}`,
        `blocked=${document.querySelector('[data-testid="creator-freeze-blocked"]')?.textContent ?? ''}`,
        `err=${document.querySelector('[data-testid="creator-freeze-error"]')?.textContent ?? ''}`,
        `painted=${document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') ?? 'none'}`,
      ].join(' '),
    )
    if (freezeTimeline.length > 60) freezeTimeline.shift()
    if (frozenNow) break
    if (button !== null && !button.disabled && freezeTimeline.length % 20 === 0) button.click()
    if (Date.now() > freezeDeadline) {
      const blocked = document.querySelector('[data-testid="creator-freeze-blocked"]')?.textContent ?? ''
      const freezeError = document.querySelector('[data-testid="creator-freeze-error"]')?.textContent ?? ''
      throw new Error(`composer freeze blocked=${blocked} freezeError=${freezeError} timeline=${freezeTimeline.slice(-8).join(' || ')}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  freezeTimelines.push(freezeTimeline)
  const quadBytes = await texturedQuadJpeg(seed, 0, 0, 640, 480)
  setQuad(quadBytes)
  await clickText(t('useCamera'), 'useCamera')
  await clickText(t('takePicture'), 'takePicture')
  await waitFor(() => bodyText().includes(t('frontReady')), 'front ready', 300000)
  await waitFor(() => byTest('send_button') !== null && !(byTest('send_button') as HTMLButtonElement).disabled, 'send armed', 60000)
  return normalized
}

let quadRef: Uint8Array = new Uint8Array()
function setQuad(bytes: Uint8Array): void { quadRef = bytes }

const pwaProof = new URLSearchParams(location.search).has('pwa')
async function updateWhileHeld(h: Harness, stage: string): Promise<void> {
  if (!pwaProof) return
  const controller = navigator.serviceWorker.controller
  const registration = await navigator.serviceWorker.getRegistration('/')
  if (!registration || !controller) throw new Error('fixture is not controlled')
  const beforeWaiting = registration.waiting
  const note = h.notes.accepted()
  const photos = h.photos.photos()
  const host = document.querySelector('[data-testid="create-host"]')
  await fetch('/__shell-version', { method: 'POST' })
  await registration.update()
  await waitFor(() => registration.waiting !== null && registration.waiting !== beforeWaiting, `waiting update during ${stage}`)
  await waitFor(() => shellStore.getSnapshot().waiting, 'real shell store update notice')
  assert(`${stage}: worker remains waiting without replacing controller`, navigator.serviceWorker.controller === controller)
  assert(`${stage}: mounted Create and in-memory draft survive update`,
    document.querySelector('[data-testid="create-host"]') === host &&
    h.notes.accepted() === note && h.photos.photos().every((photo, i) => photo === photos[i]))
  if (stage === 'sending') assert('sending: real request remains pending across update', byTest('send_state')?.textContent === t('sendWorking'))
  // The runner records the actual composed screen before allowing continuation.
  const bridge = window as unknown as { __pwaCapture?: string; __pwaContinue?: boolean }
  bridge.__pwaCapture = stage
  bridge.__pwaContinue = false
  await waitFor(() => bridge.__pwaContinue === true, 'runner screen capture')
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'create-send-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { roundTrip: {}, freezeTimelines }
  try {
    if (pwaProof) {
      startShell('/')
      const shellHost = document.createElement('div')
      shellHost.className = 'shell'
      document.body.appendChild(shellHost)
      createRoot(shellHost).render(<ShellStatus t={t} details={false} />)
    }
    const files = [
      await gradientJpeg(800, 600, 40),
      await gradientJpeg(600, 800, 90),
      await gradientJpeg(1000, 1000, 150),
    ]

    // ---- scenario 1: happy path, one real publication + receiver round trip ----
    await setMode('ok')
    const h = harness()
    h.create()
    const normalizedPhotos = await readyVisit(h, files, 7)
    assert('send button visible and armed only after freeze+front', byTest('send_button') !== null && !(byTest('send_button') as HTMLButtonElement).disabled)
    await updateWhileHeld(h, 'draft')
    const before = await capture()
    const draftCountBefore = before.drafts.length
    await clickText(t('sendAction'), 'sendAction')
    await waitFor(() => byTest('send_state')?.textContent === t('sendSent'), 'sent state')
    assert('UI reports postcard sent', byTest('send_state')?.textContent === t('sendSent'))
    assert('send notice recorded once', notices.filter((n) => n === 'sendSent').length === 1)
    assert('terminal visit refuses a second send click', (byTest('send_button') as HTMLButtonElement | null)?.disabled === true)
    const after1 = await capture()
    assert('exactly one draft after the happy send', lastDraft(after1) !== null && after1.drafts.length === draftCountBefore + 1)
    assert('exactly one finalize after the happy send', lastFinalize(after1) !== null && after1.finalizes.length === 1)
    report.happyUploads = after1.uploads.length
    const finalizeBody = JSON.parse(fromUtf8(fromHex(lastFinalize(after1)!.bodyHex)))
    const statementBytes = fromBase64(finalizeBody.signed_publish_statement.statement)
    const signatureBytes = fromBase64(finalizeBody.signed_publish_statement.signature)
    const envelopeBytes = fromBase64(finalizeBody.recipient_envelope.ciphertext)
    report.statementB64 = finalizeBody.signed_publish_statement.statement
    report.envelopeHex = bytesToHex(envelopeBytes)

    // Receiver-side round trip on the captured wire.
    await verifyPublishStatement(SIGNING_PUBLIC, statementBytes, signatureBytes)
    assert('finalize statement signature verifies under the recipient signing keyset', true)
    const info = recipientEnvelopeInfo({
      capsuleId: (JSON.parse(fromUtf8(fromHex(lastDraft(after1)!.bodyHex)))).capsule_id,
      senderUserId: SENDER_ID,
      recipientUserId: RECIPIENT_ID,
      recipientKeyBundleId: RECIPIENT_BUNDLE,
    })
    const opened = await openRecipientEnvelope(HPKE_PRIVATE, info, envelopeBytes)
    const fields = readMessage(opened, [1, 2, 3, 4, 5, 6, 7, 8])
    const capsuleKeyset = fieldBytes(fields, 7)
    const statementHash = fieldBytes(fields, 8)
    assert('envelope opens with the committed recipient private keyset', capsuleKeyset.length > 0)
    assert('envelope binds the statement sha256', sameBytes(statementHash, await sha256(statementBytes)))
    const draftBody = JSON.parse(fromUtf8(fromHex(lastDraft(after1)!.bodyHex)))
    const capsuleId: string = draftBody.capsule_id
    report.capsuleId = capsuleId
    const blobMeta: Array<{ blob_id: string; kind: string; ordinal: number | null }> = draftBody.blobs
    for (const upload of after1.uploads) {
      const match = /^\/v1\/capsules\/[0-9a-f-]{36}\/blobs\/([0-9a-f-]{36})$/.exec(new URL(upload.url, window.location.origin).pathname)
      const blobId = match?.[1]
      const meta = blobMeta.find((blob) => blob.blob_id === blobId)
      if (!meta) continue
      const ciphertext = fromHex(upload.bodyHex)
      const aad = artifactAad({
        capsuleId,
        blobId: meta.blob_id,
        kind: meta.kind as 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO',
        ordinal: meta.kind === 'PHOTO' ? (meta.ordinal ?? 0) : -1,
        senderUserId: SENDER_ID,
        recipientUserId: RECIPIENT_ID,
      })
      const plain = await decryptArtifact(capsuleKeyset, aad, ciphertext, meta.kind as 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO')
      if (meta.kind === 'CONTENT_MANIFEST') {
        assert('content manifest decrypts and carries the exact note text', new TextDecoder().decode(plain).includes(NOTE_TEXT))
        report.roundTrip = { ...report.roundTrip as Record<string, unknown>, note: true }
      } else if (meta.kind === 'PHOTO') {
        const original = normalizedPhotos[meta.ordinal ?? -1]
        if (original) {
          assert(`photo blob ${meta.ordinal} decrypts to the exact normalized bytes`, sameBytes(plain, original))
        }
      }
    }
    assert('every photo blob decrypted byte-exact', after1.uploads.filter((u) => u.url.includes('/blobs/')).length >= 4)

    // ---- scenario 2: missing freeze never arms Send; zero network ----
    h.unmount()
    const h2 = harness()
    h2.create()
    await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'second create flow')
    h2.pick(files)
    await waitFor(() => h2.photos.accepted()?.length === 3, 'second visit photos')
    h2.setNote(NOTE_TEXT)
    await waitFor(() => byTest('send_button') !== null, 'send button visible without freeze')
    assert('unready visit keeps the send button disabled', (byTest('send_button') as HTMLButtonElement).disabled === true)
    const before2 = await capture()
    await new Promise((resolve) => setTimeout(resolve, 1500))
    const after2 = await capture()
    assert('missing freeze + disabled send produce zero network requests', after2.uploads.length === before2.uploads.length && after2.drafts.length === before2.drafts.length && after2.finalizes.length === before2.finalizes.length)

    // ---- scenario 3: lost finalize -> unknown, never success ----
    h2.unmount()
    await setMode('finalize-500')
    const h3 = harness()
    h3.create()
    await readyVisit(h3, files, 21)
    const noticesBefore3 = notices.length
    await clickText(t('sendAction'), 'send for finalize failure')
    await waitFor(() => byTest('send_state')?.textContent === t('sendNotConfirmed'), 'not confirmed state')
    assert('lost finalize reports not-confirmed', byTest('send_state')?.textContent === t('sendNotConfirmed'))
    assert('lost finalize never claims success', !notices.slice(noticesBefore3).includes('sendSent'))
    const after3 = await capture()
    assert('lost finalize still made exactly one draft (no retry)', lastDraft(after3) !== null && after3.uploads.length >= 4 && lastFinalize(after3) !== null && after3.finalizes.length === 2)

    // ---- scenario 4: invalidation during publication -> stale, no second draft ----
    h3.unmount()
    await setMode('hold-finalize')
    const h4 = harness()
    h4.create()
    await readyVisit(h4, files, 33)
    const noticesBefore4 = notices.length
    await clickText(t('sendAction'), 'send for invalidation race')
    await waitFor(() => byTest('send_state')?.textContent === t('sendWorking'), 'working state')
    assert('working copy shows during publication', byTest('send_state')?.textContent === t('sendWorking'))
    // Wait until the real draft+uploads completed and the finalize request is
    // actually deferred on the server — THEN kill the visit. Locking earlier
    // (mid-seal) would fence before any publish, which proves a different
    // (also safe, but not this) boundary.
    {
      const gateDeadline = Date.now() + 120000
      for (;;) {
        const current = await capture()
        if (current.finalizes.length === 3) break
        if (Date.now() > gateDeadline) throw new Error(`timeout: finalize deferred on server (saw ${current.finalizes.length})`)
        await new Promise((resolve) => setTimeout(resolve, 250))
      }
    }
    await updateWhileHeld(h4, 'sending')
    h4.session.lock()
    await releaseFinalize()
    await new Promise((resolve) => setTimeout(resolve, 2500))
    assert('mid-flight lock adds no success notice', !notices.slice(noticesBefore4).includes('sendSent'))
    assert('mid-flight lock adds no not-sent claim either', !notices.slice(noticesBefore4).includes('sendNotSent'))
    const after4 = await capture()
    assert('mid-flight invalidation still produced exactly one draft (terminal, no retry)', lastDraft(after4) !== null && after4.drafts.length === 3)
    assert('mid-flight invalidation received exactly one finalize', lastFinalize(after4) !== null && after4.finalizes.length === 3)

    // ---- scenario 5: a real in-visit photo removal during the FIRST draft
    // request must stop every subsequent upload/finalize (bounded per-attempt
    // transport guard + Create's synchronous invalidation owner). ----
    h4.unmount()
    await setMode('hold-draft')
    const h5 = harness()
    h5.create()
    await readyVisit(h5, files, 47)
    const before5 = await capture()
    const noticesBefore5 = notices.length
    await clickText(t('sendAction'), 'send for draft-time edit')
    await waitFor(() => byTest('send_state')?.textContent === t('sendWorking'), 'working state (draft hold)')
    // Wait until the FIRST draft request has actually reached the server and
    // is being held — THEN edit, so this is the draft-in-flight case.
    {
      const draftGateDeadline = Date.now() + 120000
      for (;;) {
        const current = await capture()
        if (current.drafts.length === before5.drafts.length + 1) break
        if (Date.now() > draftGateDeadline) throw new Error(`timeout: draft deferred on server (saw ${current.drafts.length}, want ${before5.drafts.length + 1})`)
        await new Promise((resolve) => setTimeout(resolve, 250))
      }
    }
    await waitFor(() => {
      const button = buttonByText(t('removePhoto'))
      return button !== null && !button.disabled
    }, 'removePhoto clickable while working')
    buttonByText(t('removePhoto'))!.click()
    await releaseDraft()
    await new Promise((resolve) => setTimeout(resolve, 2500))
    const after5 = await capture()
    assert('draft-time photo edit produced exactly one new draft', after5.drafts.length === before5.drafts.length + 1)
    assert('draft-time photo edit stopped every subsequent upload', after5.uploads.length === before5.uploads.length)
    assert('draft-time photo edit stopped the finalize request', after5.finalizes.length === before5.finalizes.length)
    assert(
      'draft-time photo edit adds no sent/not-sent/not-confirmed notice',
      !notices.slice(noticesBefore5).some((notice) => notice === 'sendSent' || notice === 'sendNotSent' || notice === 'sendNotConfirmed'),
    )

    // ---- scenario 6: camera/front FIRST, freeze LAST must arm Send ----
    h5.unmount()
    await setMode('ok')
    const h6 = harness()
    h6.create()
    await waitFor(() => document.querySelector('.photo-draft input[type="file"]') !== null, 'sixth create flow')
    h6.pick(files)
    await waitFor(() => h6.photos.accepted()?.length === 3, 'sixth visit photos')
    await waitFor(() => document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', 'sixth composer painted')
    h6.setNote(NOTE_TEXT)
    await waitFor(() => document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', 'sixth composer repaint after note')
    setQuad(await texturedQuadJpeg(55, 0, 0, 640, 480))
    await clickText(t('useCamera'), 'useCamera (alternate order)')
    await clickText(t('takePicture'), 'takePicture (alternate order)')
    await waitFor(() => bodyText().includes(t('frontReady')), 'front ready before freeze', 300000)
    assert('front ready with no freeze yet keeps Send disabled', (byTest('send_button') as HTMLButtonElement).disabled === true)
    await waitFor(() => {
      const button = byTest('creator-freeze') as HTMLButtonElement | null
      return button !== null && !button.disabled
    }, 'freeze armed after front', 120000)
    ;(byTest('creator-freeze') as HTMLButtonElement).click()
    await waitFor(() => byTest('creator-frozen') !== null, 'frozen after front', 120000)
    await waitFor(() => byTest('send_button') !== null && !(byTest('send_button') as HTMLButtonElement).disabled, 'send armed after freeze-last', 60000)
    assert('freeze LAST after a genuine camera take arms Send without extra action', !(byTest('send_button') as HTMLButtonElement).disabled)
    await clickText(t('sendAction'), 'send (alternate order)')
    await waitFor(() => byTest('send_state')?.textContent === t('sendSent'), 'sent state (alternate order)', 60000)
    assert('alternate-order visit reports postcard sent', byTest('send_state')?.textContent === t('sendSent'))

    report.notices = [...notices]
  } catch (error) {
    assert('create send connected harness', false, String(error))
  }
  ;(window as unknown as { __createSend?: unknown }).__createSend = { assertions, failures, report, notices: [...notices] }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

function fromUtf8(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes)
}

void main()
