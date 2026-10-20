/**
 * T349 connected proof (Chromium-only): the ACTUAL App/Scan default path uses
 * the REAL receiving engine end to end.
 *
 * A genuine sender producer wire (synthetic textured CameraPort through the
 * accepted front-fingerprint producer) is sealed by the REAL `sealCapsule`
 * (canvas-decodable JPEG photos, not header-only fixtures) and served over a
 * same-origin loopback catalog/signing/blob harness. The exported `App`
 * receives through the production default engine (no injected engine):
 * positive unique match -> real grant/receive/decrypt -> presentation-admitted
 * CollageStage; two distinct capsule IDs sharing one reference -> truthful
 * ambiguous notice with ZERO content/photo reads; a `has_more` page -> truthful
 * incomplete notice with ZERO body reads; an explicit injected unavailable
 * engine still refuses; locking during a pending match never admits a view.
 */
import { createRoot, type Root } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import type { PreferencePort } from '../../src/preferences/language'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { fetchTransport } from '../../src/auth/http'
import { signPublishStatement } from '../../src/crypto/publish'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import { decryptArtifact } from '../../src/crypto/artifact'
import type { KeyCapabilities } from '../../src/crypto/keyOwner'
import { PresentationMemory, browserUrls } from '../../src/collage/memory'
import { FrontHold } from '../../src/create/front'
import { FrontFingerprintHold } from '../../src/create/frontFingerprint'
import { sealCapsule } from '../../src/create/sealCapsule'
import type { SealedCapsule } from '../../src/create/sealCapsule'
import { layoutCreatorNote } from '../../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../../src/collage/frozenRenderedPlan'

import type { TextMeasurer } from '../../src/collage/prepare'
import { styleById } from '../../src/collage/styles'
import { freezeSelection, type CreatorFreeze } from '../../src/create/creatorFreeze'
import type { DraftPhoto } from '../../src/create/photos'
import type { CameraPort, StillCapture } from '../../src/scan/camera'
import { unavailableRecognition, type RecognitionEngine } from '../../src/scan/grant'
import { bytesToHex } from '../../src/protocol/bytes'
import { catalog as i18nCatalog, type MessageKey, type Language } from '../../src/i18n/catalog'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const notices: MessageKey[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const SENDER_ID = '11111111-2222-4333-8444-555555555555'
const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
const SENDER_HANDLE = 'sergio'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const NOTE_TEXT = 'hello from the recognition index proof'

const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
const HPKE_PRIVATE = keysetFromJson(hpkeVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

function recipientSnapshot(): RecipientSnapshot {
  return {
    userId: RECIPIENT_ID, handle: 'ada', keyBundleId: RECIPIENT_BUNDLE,
    suite: SUITE, protocolVersion: 1,
    encryptionPublicKeyset: b64(HPKE_PUBLIC), signingPublicKeyset: b64(SIGNING_PUBLIC),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

function senderSnapshot(): RecipientSnapshot {
  return {
    userId: SENDER_ID, handle: SENDER_HANDLE, keyBundleId: SENDER_BUNDLE,
    suite: SUITE, protocolVersion: 1,
    encryptionPublicKeyset: b64(HPKE_PUBLIC), signingPublicKeyset: b64(SIGNING_PUBLIC),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
}

function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/** Canonical server-compatible version-1 cursor (25 bytes -> 34 chars). */
function canonicalCursor(sequence: number): string {
  const payload = new Uint8Array(25)
  payload[0] = 1
  new DataView(payload.buffer).setBigInt64(1, BigInt(sequence), false)
  payload.fill(0x5a, 9)
  return base64url(payload)
}

async function canvasJpeg(width: number, height: number, seed: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const random = mulberry32(seed)
  for (let i = 0; i < 80; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(random() * width, random() * height, 20, 20)
  }
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

async function jpeg(id: string, width: number, height: number): Promise<DraftPhoto> {
  const seed = 1000 + id.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)
  return { id, bytes: await canvasJpeg(width, height, seed), width, height, contentHash: '' }
}

async function buildFreeze(photos: readonly DraftPhoto[], note: string, generation: number): Promise<CreatorFreeze> {
  const withHashes = await Promise.all(photos.map(async (photo) => ({ ...photo, contentHash: bytesToHex(await sha256(photo.bytes)) })))
  const source = withHashes.map((photo) => ({ contentId: photo.id, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
  const byId = new Map(source.map((spec) => [spec.contentId, spec]))
  const style = styleById('press')!
  const view = previewCandidates({ id: 'creator', sender: SENDER_HANDLE, photos: source, note }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view, styleId: 'press', senderHandle: SENDER_HANDLE, noteText: note,
    photos: new Map(source.map((spec) => [spec.contentId, { width: spec.width, height: spec.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) => measurer.measure({ text: line, family: scene.note!.family, size: scene.note!.size, weight: scene.note!.weight, italic: scene.note!.italic, line: scene.note!.line, maxWidth: Number.POSITIVE_INFINITY, maxHeight: Number.POSITIVE_INFINITY, softWrap: false }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: 'press', styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch, styleId: 'press', styleVersion: style.version, senderHandle: SENDER_HANDLE,
    slots: view.slots.map((slot, index) => {
      const spec = byId.get(slot.photoId)!
      return { contentId: slot.photoId, ordinal: index, contentHash: spec.contentHash, widthPx: spec.width, heightPx: spec.height, crop: { ...slot.crop } }
    }),
    note, noteRegion: scene.note ? { ...scene.note.region } : null, noteFits: true, musicSelected: false,
    ownerId: SENDER_ID, sessionGeneration: generation,
    recipient: { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: '0'.repeat(16) },
    renderedKey: 'rk-1', renderedPlan, createdAtMs: 1,
  })
  if (!result.ok) throw new Error('freeze failed')
  return result.frozen
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

async function texturedQuadJpeg(seed: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100, y: 80 }, { x: 540, y: 60 }, { x: 560, y: 400 }, { x: 80, y: 420 },
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

async function waitFor(cond: () => boolean, what: string, timeoutMs = 120000): Promise<void> {
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

function bodyText(): string {
  return document.body.textContent ?? ''
}

async function postJson(path: string, body: unknown): Promise<void> {
  const response = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  if (!response.ok) throw new Error(`post ${path} failed`)
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) throw new Error(`get ${path} failed`)
  return await response.json() as T
}

const t = (key: MessageKey): string => i18nCatalog.en[key]

function preferences(): PreferencePort {
  const values = new Map<string, string>()
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => { values.set(key, value) },
    remove: (key) => { values.delete(key) },
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'scan-engine-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { pass: false }
  try {
    // ---- sender session + REAL genuine producer wire + REAL seals ----
    const senderStore = new SessionStore('en')
    senderStore.signIn({
      accountId: SENDER_ID, bundleId: SENDER_BUNDLE, handle: SENDER_HANDLE,
      accessToken: 'a', refreshToken: 'r',
      accessExpiresAt: '2026-10-20T00:00:00Z', refreshExpiresAt: '2026-11-20T00:00:00Z',
    })
    senderStore.unlock()
    const senderCaps: KeyCapabilities = {
      accountId: SENDER_ID, bundleId: SENDER_BUNDLE,
      openEnvelope: async () => { throw new Error('unused') },
      decryptArtifact: async () => { throw new Error('unused') },
      sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
      lock: () => {},
    }
    const senderKeys = new MemoryKeys()
    senderKeys.hold(senderStore.getSnapshot(), senderCaps)

    const producerRecipient = new RecipientDraft()
    producerRecipient.attach(senderStore)
    const producerTicket = producerRecipient.nextTicket(SENDER_ID)
    producerRecipient.offer(producerTicket, SENDER_ID, senderSnapshot())
    producerRecipient.confirm()
    const quad = await texturedQuadJpeg(7, 640, 480)
    const producerHold = new FrontHold()
    await producerHold.open({
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async (): Promise<StillCapture> => ({ bytes: quad.slice(), width: 640, height: 480 }),
        stop: () => undefined,
      }),
    })
    const still = await producerHold.take(producerRecipient.confirmed()!)
    const holder = new FrontFingerprintHold()
    const produced = await holder.run(still, {
      store: senderStore,
      recipient: producerRecipient,
      hold: producerHold,
      mounted: () => true,
    })
    assert('genuine producer wire is nonempty format-3', produced.wire.length > 0)

    const photoDrafts = await Promise.all([jpeg('p0', 300, 400), jpeg('p1', 300, 400), jpeg('p2', 300, 400)])
    const hashedPhotos = await Promise.all(photoDrafts.map(async (photo) => ({
      ...photo,
      contentHash: bytesToHex(await sha256(photo.bytes)),
    })))
    const freeze = await buildFreeze(hashedPhotos, NOTE_TEXT, senderStore.getSnapshot().generation)
    const ordered = freeze.slots.map((slot) => hashedPhotos.find((photo) => photo.id === slot.contentId)!)

    const sealOne = async (capsuleId: string): Promise<SealedCapsule> =>
      sealCapsule({
        session: senderStore,
        keys: senderKeys,
        freeze,
        recipient: recipientSnapshot(),
        photos: ordered,
        capsuleId,
        candidateId: `cand-${capsuleId.slice(0, 4)}`,
        createdAtEpochSeconds: 1_755_000_000n,
        epoch: 1n,
        frontFingerprint: produced.wire,
        placeLabel: null,
      })

    const wireItem = async (sealed: SealedCapsule) => ({
      capsule_id: sealed.capsuleId,
      sender_user_id: sealed.senderUserId,
      recipient_user_id: sealed.recipientUserId,
      sender_key_bundle_id: sealed.senderKeyBundleId,
      recipient_key_bundle_id: sealed.recipientKeyBundleId,
      protocol_version: 1,
      ready_at: '2026-10-20T01:00:00Z',
      signed_publish_statement: {
        statement: b64(sealed.statement),
        statement_sha256: b64(sealed.statementSha256),
        signature: b64(sealed.signature),
      },
      recipient_envelope: {
        recipient_key_bundle_id: sealed.recipientKeyBundleId,
        ciphertext: b64(sealed.envelopeCiphertext),
        ciphertext_size: sealed.envelopeCiphertext.length,
        ciphertext_sha256: b64(await sha256(sealed.envelopeCiphertext)),
      },
      blobs: sealed.blobs.map((blob) => ({
        blob_id: blob.blobId,
        kind: blob.kind,
        ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null,
        ciphertext_size: blob.ciphertextSize,
        ciphertext_sha256: b64(blob.ciphertextSha256),
      })),
    })

    const sealed1 = await sealOne(crypto.randomUUID())
    const sealed2 = await sealOne(crypto.randomUUID())
    const item1 = await wireItem(sealed1)
    const item2 = await wireItem(sealed2)
    // Distinct visual reference so a cross-page unique page can contain a
    // non-matching capsule before the matching one.
    const otherQuad = await texturedQuadJpeg(99, 640, 480)
    const otherHold = new FrontHold()
    await otherHold.open({
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async (): Promise<StillCapture> => ({ bytes: otherQuad.slice(), width: 640, height: 480 }),
        stop: () => undefined,
      }),
    })
    const otherStill = await otherHold.take(producerRecipient.confirmed()!)
    const otherHolder = new FrontFingerprintHold()
    const otherProduced = await otherHolder.run(otherStill, {
      store: senderStore,
      recipient: producerRecipient,
      hold: otherHold,
      mounted: () => true,
    })
    const sealedOther = await sealCapsule({
      session: senderStore,
      keys: senderKeys,
      freeze,
      recipient: recipientSnapshot(),
      photos: ordered,
      capsuleId: crypto.randomUUID(),
      candidateId: 'cand-other',
      createdAtEpochSeconds: 1_755_000_000n,
      epoch: 1n,
      frontFingerprint: otherProduced.wire,
      placeLabel: null,
    })
    const itemOther = await wireItem(sealedOther)
    await postJson('/__signing', {
      key_bundle_id: sealed1.senderKeyBundleId,
      user_id: sealed1.senderUserId,
      protocol_version: 1,
      status: 'ACTIVE',
      signing_public_keyset: b64(SIGNING_PUBLIC),
    })
    await postJson('/__blobs', [...sealed1.blobs, ...sealed2.blobs, ...sealedOther.blobs].map((blob) => ({ blobId: blob.blobId, bodyHex: bytesToHex(blob.ciphertext) })))
    const serveCatalog = async (items: unknown[], hasMore = false, nextCursor: string | null = null) => {
      await postJson('/__catalog', { items, has_more: hasMore, next_cursor: nextCursor })
    }
    const servePages = async (pages: Array<{ cursor: string | null; items: unknown[]; hasMore: boolean; nextCursor: string | null }>) => {
      await postJson('/__catalog', {
        pages: pages.map((page) => ({ cursor: page.cursor, items: page.items, has_more: page.hasMore, next_cursor: page.nextCursor })),
      })
    }

    // ---- recipient helper + REAL App mount (production default engine) ----
    let prepareGate: Promise<void> | null = null
    const preparedBytes: Uint8Array[] = []
    const makeRecipient = () => {
      const store = new SessionStore('en')
      store.signIn({
        accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE, handle: 'ada',
        accessToken: 'a', refreshToken: 'r',
        accessExpiresAt: '2026-10-20T00:00:00Z', refreshExpiresAt: '2026-11-20T00:00:00Z',
      })
      store.unlock()
      const caps: KeyCapabilities = {
        accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE,
        openEnvelope: (info, ciphertext) => openRecipientEnvelope(HPKE_PRIVATE, info, ciphertext),
        decryptArtifact: async (...args) => {
          const bytes = await decryptArtifact(...args)
          preparedBytes.push(bytes)
          if (args[3] === 'PHOTO' && prepareGate) await prepareGate
          return bytes
        },
        sign: async () => { throw new Error('unused') },
        lock: () => {},
      }
      const keys = new MemoryKeys()
      keys.attach(store)
      keys.hold(store.getSnapshot(), caps)
      const liveUrls = new Set<string>(), heldCopies: Uint8Array[] = []
      const memory = new PresentationMemory({
        create(bytes, type) { heldCopies.push(bytes); const url = browserUrls.create(bytes, type); liveUrls.add(url); return url },
        revoke(url) { liveUrls.delete(url); browserUrls.revoke(url) },
      })
      memory.attach(store) // Same lifecycle wiring as production main.tsx.
      return { store, keys, memory, caps, liveUrls, heldCopies }
    }

    let scanQuad: Uint8Array = quad.slice()
    let cameraStops = 0
    const capturedBytes: Uint8Array[] = []
    const scanCamera: CameraPort = {
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async (): Promise<StillCapture> => {
          const bytes = scanQuad.slice(); capturedBytes.push(bytes)
          return { bytes, width: 640, height: 480 }
        },
        stop: () => { cameraStops++ },
      }),
    }

    // T392 regression: delay delivery of the original HTTP result, not its
    // request/commit. A native event releases it before passive React cleanup.
    let delayedAdmission: { wait: Promise<void>; entered: () => void } | null = null
    let lastAdmissionSignal: AbortSignal | undefined
    const realTransport = fetchTransport(window.location.origin)
    const scheduledTransport = { ...realTransport, async request(input: Parameters<typeof realTransport.request>[0]) {
      const gate = input.path.endsWith('/first-open') ? delayedAdmission : null
      if (gate) lastAdmissionSignal = input.signal
      const response = await realTransport.request(input)
      if (gate) { gate.entered(); await gate.wait }
      return response
    } }
    const rootRef: { current: Root | null } = { current: null }
    const mountApp = (rec: ReturnType<typeof makeRecipient>, engine?: RecognitionEngine): void => {
      rootRef.current?.unmount()
      window.location.hash = '#/scan'
      const host = document.createElement('div')
      host.setAttribute('data-testid', 'app-host')
      // App owns the shell; a second shell distorts responsive proof and leaves
      // padded empty hosts behind after sequential fixture visits.
      document.body.appendChild(host)
      rootRef.current = createRoot(host)
      rootRef.current.render(
        <App
          store={rec.store}
          preferences={preferences()}
          keys={rec.keys}
          memory={rec.memory}
          transport={scheduledTransport}
          engine={engine}
          camera={scanCamera}
          recipient={new RecipientDraft()}
          note={new NoteDraft()}
          photos={new PhotoDraft()}
          music={new MusicDraft()}
        />,
      )
    }
    const runScan = async (what: string): Promise<void> => {
      await waitFor(() => buttonByText(t('useCamera')) !== null, `${what} camera`)
      await clickText(t('useCamera'), `${what} open`)
      await clickText(t('takePicture'), `${what} take`)
      await waitFor(() => buttonByText(t('matchPicture')) !== null, `${what} match`)
      await clickText(t('matchPicture'), `${what} match click`)
    }
    const captureCounts = () => getJson<{
      recognition: number
      content: number
      photo: number
      admissions: Array<{ capsule: string; bearer: boolean; bodyLength: number; content: number; photo: number }>
      incoming?: Array<{ cursor: string | null; limit: string | null }>
    }>('/__capture')
    const waitNotice = (key: MessageKey) => waitFor(() => bodyText().includes(t(key)), `notice ${key}`)
    const stageShown = () => document.querySelector('.stage') !== null

    // ---- POSITIVE: production DEFAULT engine opens the exact sealed capsule ----
    // Real server terminal semantics: has_more=false with a NON-NULL cursor.
    await serveCatalog([item1], false, canonicalCursor(1))
    let rec = makeRecipient()
    const beforePositive = await captureCounts()
    mountApp(rec)
    await runScan('positive')
    await waitFor(() => rec.store.getSnapshot().phase === 'presentation-admitted', 'presentation admitted', 60000)
    await waitFor(() => stageShown(), 'CollageStage displayed', 60000)
    const positiveCounts = await captureCounts()
    const memorySheet = rec.memory.getSnapshot()
    assert('positive: phase is presentation-admitted', rec.store.getSnapshot().phase === 'presentation-admitted')
    assert('positive: CollageStage is displayed', stageShown())
    assert('positive: memory has three photos', memorySheet !== null && memorySheet.scene.photos.length === 3)
    assert('positive: memory note is the sealed note', memorySheet !== null && memorySheet.scene.note?.text === NOTE_TEXT)
    assert('positive: content manifest downloaded only after unique match', positiveCounts.content > beforePositive.content)
    assert('positive: photo blobs downloaded only after unique match', positiveCounts.photo > beforePositive.photo)
    assert('positive: exactly one authenticated admission after all preparation', positiveCounts.admissions.length === beforePositive.admissions.length + 1
      && positiveCounts.admissions.at(-1)?.bearer === true && positiveCounts.admissions.at(-1)?.bodyLength === 0)
    report.positive = { phase: rec.store.getSnapshot().phase, photos: memorySheet?.scene.photos.length ?? 0 }
    rootRef.current?.unmount()

    // T388 actual App/default engine/real crypto with controlled admission HTTP.
    // Cached, still-readable encrypted bodies must not bypass a revoke denial.
    const allZero = () => preparedBytes.every((bytes) => bytes.every((value) => value === 0))
    for (const [mode, notice] of [['deny', 'openDenied'], ['unknown', 'openUnconfirmed'], ['lost', 'openUnconfirmed'],
      ['offline', 'openUnconfirmed'], ['auth', 'openSignIn']] as const) {
      await postJson('/__admission', { mode })
      rec = makeRecipient(); mountApp(rec)
      const before = await captureCounts()
      await runScan(`admission ${mode}`)
      await waitNotice(notice)
      const after = await captureCounts()
      assert(`${mode}: prepared cached bodies cannot display without admission`, after.content > before.content && after.photo > before.photo
        && after.admissions.length > before.admissions.length && !stageShown() && rec.memory.getSnapshot() === null)
      assert(`${mode}: owned retry phase and plaintext wiped`, rec.store.getSnapshot().phase === 'keys-unlocked' && allZero())
      if (mode === 'deny' || mode === 'lost') {
        for (const language of ['en', 'ru', 'uk'] as Language[]) {
          rec.store.setLanguage(language)
          await waitFor(() => bodyText().includes(i18nCatalog[language][notice]), 'localized admission error')
          for (const width of [320, 1280]) {
            const shot = await fetch('/__shot', { method: 'POST', body: JSON.stringify({ name: `${mode}-${language}-${width}`, width }) }).then((r) => r.json())
            assert(`${mode} ${language} ${width}: no horizontal overflow`, shot.overflow === false)
          }
        }
        rec.store.setLanguage('en')
      }
      await postJson('/__admission', { mode: 'ok' })
      await clickText(t('matchPicture'), 'explicit retry same held capture')
      await waitFor(() => stageShown(), 'replay after failed admission')
      assert(`${mode}: explicit replay displays three photos`, rec.memory.getSnapshot()?.photos.size === 3)
      rec.store.lock(); await waitFor(() => !stageShown(), 'clear admitted replay')
      assert(`${mode}: close wipes held plaintext`, rec.memory.getSnapshot() === null && allZero())
      rootRef.current?.unmount()
    }
    for (const interruption of ['cancel', 'back', 'logout', 'reentry', 'owner'] as const) {
      rec = makeRecipient(); mountApp(rec)
      await postJson('/__admission', { mode: 'ok', hold: true })
      const before = await captureCounts()
      await runScan(`pending admission ${interruption}`)
      await waitFor(() => bodyText().includes(t('openChecking')), 'waiting admission UI')
      // The next control request is ordered after the actual first-open request
      // by waiting on the host's captured counter, not a fixed sleep.
      const deadline = Date.now() + 120000
      while ((await captureCounts()).admissions.length === before.admissions.length) {
        if (Date.now() > deadline) throw new Error('admission request did not arrive')
        await new Promise((ok) => setTimeout(ok, 20))
      }
      assert(`${interruption}: no presentation while admission unresolved`, !stageShown() && rec.memory.getSnapshot() === null)
      if (interruption === 'cancel') await clickText(t('closeCamera'), 'cancel admission')
      else if (interruption === 'back') { location.hash = '#/'; await waitFor(() => !buttonByText(t('matchPicture')), 'back unmount') }
      else {
        rec.store.signOut()
        if (interruption !== 'logout') {
          rec.store.signIn({ accountId: interruption === 'owner' ? SENDER_ID : RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE,
            handle: 'replacement', accessToken: 'new', refreshToken: 'new', accessExpiresAt: '', refreshExpiresAt: '' })
        }
      }
      const newer = rec.store.getSnapshot()
      await postJson('/__admission', { mode: 'ok', hold: false })
      await waitFor(allZero, 'late prepared bytes wiped')
      if (interruption === 'cancel') {
        await waitFor(() => document.activeElement === buttonByText(t('useCamera')), 'cancel returns camera focus')
        assert('cancel admission restores camera keyboard focus', document.activeElement === buttonByText(t('useCamera')))
      }
      assert(`${interruption}: late reply cannot present or overwrite newer owner`, !stageShown() && rec.memory.getSnapshot() === null
        && (interruption === 'cancel' || interruption === 'back' ? rec.store.getSnapshot().phase === 'keys-unlocked' : rec.store.getSnapshot() === newer))
      rootRef.current?.unmount()
    }
    // Abort during local decrypt/preparation must never send first-open.
    rec = makeRecipient(); mountApp(rec)
    let releasePrepare!: () => void
    prepareGate = new Promise<void>((ok) => { releasePrepare = ok })
    const beforePrepare = await captureCounts(), beforeBytes = preparedBytes.length
    await runScan('abort local preparation')
    await waitFor(() => preparedBytes.length >= beforeBytes + 3, 'photo decrypt held')
    await clickText(t('closeCamera'), 'abort during local preparation')
    prepareGate = null; releasePrepare()
    await waitFor(allZero, 'aborted preparation bytes zero')
    assert('prepare cancellation never admits or presents', (await captureCounts()).admissions.length === beforePrepare.admissions.length
      && !stageShown() && rec.memory.getSnapshot() === null && rec.store.getSnapshot().phase === 'keys-unlocked')
    rootRef.current?.unmount()

    // Real native Back/popstate, not a hash assignment followed by unmount.
    // Keep the original reviewer's response/event ordering as the RED control.
    for (const navigationKind of ['hash', 'browser-back'] as const) {
      history.replaceState(null, '', '#/')
      rec = makeRecipient(); mountApp(rec)
      let reached!: () => void, releaseResponse!: () => void
      const entered = new Promise<void>((ok) => { reached = ok })
      const wait = new Promise<void>((ok) => { releaseResponse = ok })
      delayedAdmission = { wait, entered: reached }
      await runScan('native navigation ' + navigationKind)
      await entered
      const stopsBeforeDeparture = cameraStops
      let synchronousCleanup = false
      let offRouteAdmission = false
      const stopObservation = rec.store.subscribe(() => {
        if (rec.store.getSnapshot().phase === 'presentation-admitted' && location.hash !== '#/scan') offRouteAdmission = true
      })
      window.addEventListener(navigationKind === 'hash' ? 'hashchange' : 'popstate', () => {
        synchronousCleanup = lastAdmissionSignal?.aborted === true && cameraStops > stopsBeforeDeparture
          && capturedBytes.at(-1)!.every((byte) => byte === 0)
        releaseResponse()
      }, { once: true })
      if (navigationKind === 'hash') location.hash = '#/'
      else history.back()
      await new Promise((ok) => setTimeout(ok, 300))
      assert('native ' + navigationKind + ': no admission after route departure', !offRouteAdmission)
      assert('native ' + navigationKind + ': late reply leaves no sheet', rec.memory.getSnapshot() === null && rec.store.getSnapshot().phase === 'keys-unlocked')
      assert('native ' + navigationKind + ': synchronous abort/camera/capture cleanup', synchronousCleanup)
      assert('native ' + navigationKind + ': decrypted bytes zero and no URLs', allZero() && rec.liveUrls.size === 0)
      report['native-' + navigationKind] = { offRouteAdmission, phase: rec.store.getSnapshot().phase, sheet: rec.memory.getSnapshot() !== null, hash: location.hash }
      await fetch('/__shot', { method: 'POST', body: JSON.stringify({ name: 'native-' + navigationKind, width: 320 }) })
      delayedAdmission = null
      stopObservation()
      if (navigationKind === 'browser-back') {
        history.forward()
        await waitFor(() => location.hash === '#/scan' && buttonByText(t('useCamera')) !== null, 'forward returns idle scan')
        assert('forward cannot resurrect cancelled admission', rec.memory.getSnapshot() === null && !stageShown())
        await runScan('explicit scan after forward')
        await waitFor(stageShown, 'forward explicit replay')
        assert('forward explicit replay admits real photos and URLs', rec.memory.getSnapshot()?.photos.size === 3 && rec.liveUrls.size === 3)
      }
      rec.store.lock()
      assert('native ' + navigationKind + ': eventual held copies/URLs released', rec.liveUrls.size === 0
        && rec.heldCopies.every((bytes) => bytes.every((byte) => byte === 0)))
      rootRef.current?.unmount()
    }

    // Local crypto completion at the same native event boundary must not even
    // request first-open. Unlike the response case there is no server commit.
    for (const navigationKind of ['hash', 'browser-back'] as const) {
      history.replaceState(null, '', '#/')
      rec = makeRecipient(); mountApp(rec)
      prepareGate = new Promise<void>((ok) => { releasePrepare = ok })
      const before = await captureCounts(), byteCount = preparedBytes.length
      await runScan('native prepare ' + navigationKind)
      await waitFor(() => preparedBytes.length >= byteCount + 3, 'native photo decrypt held')
      const stops = cameraStops
      let syncCleanup = false
      window.addEventListener(navigationKind === 'hash' ? 'hashchange' : 'popstate', () => {
        syncCleanup = cameraStops > stops && capturedBytes.at(-1)!.every((byte) => byte === 0)
        prepareGate = null; releasePrepare()
      }, { once: true })
      if (navigationKind === 'hash') location.hash = '#/'
      else history.back()
      await waitFor(() => location.hash === '#/' && allZero(), 'native prepared bytes wiped')
      assert('native prepare ' + navigationKind + ': synchronous camera/capture cleanup', syncCleanup)
      assert('native prepare ' + navigationKind + ': zero first-open, sheet, URLs', (await captureCounts()).admissions.length === before.admissions.length
        && rec.memory.getSnapshot() === null && rec.liveUrls.size === 0 && rec.store.getSnapshot().phase === 'keys-unlocked')
      rootRef.current?.unmount()
    }

    // A newer completed sheet in the SAME stores must survive both a late
    // success and a late transport failure from the old session generation.
    for (const mode of ['ok', 'unknown'] as const) {
      rec = makeRecipient(); mountApp(rec)
      await postJson('/__admission', { mode })
      let reached!: () => void, release!: () => void
      const entered = new Promise<void>((ok) => { reached = ok })
      delayedAdmission = { wait: new Promise<void>((ok) => { release = ok }), entered: reached }
      const oldBytesStart = preparedBytes.length
      await runScan('old owner ' + mode); await entered
      const oldBytes = preparedBytes.slice(oldBytesStart)
      rec.store.signOut()
      const oldSignalAborted = lastAdmissionSignal?.aborted === true
      rec.store.signIn({ accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE, handle: 'ada',
        accessToken: 'new', refreshToken: 'new', accessExpiresAt: '', refreshExpiresAt: '' })
      rec.store.unlock()
      rec.keys.hold(rec.store.getSnapshot(), { ...rec.caps })
      delayedAdmission = null
      await postJson('/__admission', { mode: 'ok' })
      await runScan('new owner ' + mode)
      await waitFor(stageShown, 'newer owner sheet')
      const newerSession = rec.store.getSnapshot(), newerSheet = rec.memory.getSnapshot()
      const urls = [...rec.liveUrls], copies = rec.heldCopies.slice()
      release()
      await waitFor(() => oldBytes.every((bytes) => bytes.every((byte) => byte === 0)), 'old owner private bytes wiped')
      assert('newer owner survives late ' + mode + ' unchanged', oldSignalAborted && rec.store.getSnapshot() === newerSession
        && rec.memory.getSnapshot() === newerSheet && urls.every((url) => rec.liveUrls.has(url))
        && copies.every((bytes) => bytes.some((byte) => byte !== 0)) && stageShown())
      rec.store.lock()
      assert('newer owner ' + mode + ': own final cleanup wipes copies/revokes URLs', rec.liveUrls.size === 0
        && copies.every((bytes) => bytes.every((byte) => byte === 0)))
      rootRef.current?.unmount()
    }

    // ---- MANY: two distinct IDs sharing the same genuine visual reference ----
    await serveCatalog([item1, item2])
    rec = makeRecipient()
    const beforeMany = await captureCounts()
    mountApp(rec)
    await runScan('many')
    await waitNotice('ambiguousMatch')
    const manyCounts = await captureCounts()
    assert('many: ambiguous notice is shown', bodyText().includes(t('ambiguousMatch')))
    assert('many: no CollageStage', !stageShown())
    assert('many: zero content reads', manyCounts.content === beforeMany.content)
    assert('many: zero photo reads', manyCounts.photo === beforeMany.photo)
    assert('many: phase stays keys-unlocked', rec.store.getSnapshot().phase === 'keys-unlocked')
    report.many = manyCounts
    rootRef.current?.unmount()

    // ---- INCOMPLETE later page fails closed, nothing opened ----
    const incompleteCursor = canonicalCursor(2)
    await servePages([
      { cursor: null, items: [item1], hasMore: true, nextCursor: incompleteCursor },
      { cursor: incompleteCursor, items: [], hasMore: true, nextCursor: incompleteCursor },
    ])
    rec = makeRecipient()
    const beforeIncomplete = await captureCounts()
    mountApp(rec)
    await runScan('incomplete')
    await waitNotice('incompleteIndex')
    const incompleteCounts = await captureCounts()
    assert('incomplete: bounded incomplete notice', bodyText().includes(t('incompleteIndex')))
    assert('incomplete: no CollageStage', !stageShown())
    assert('incomplete: zero content reads', incompleteCounts.content === beforeIncomplete.content)
    assert('incomplete: zero photo reads', incompleteCounts.photo === beforeIncomplete.photo)
    report.incomplete = incompleteCounts
    rootRef.current?.unmount()

    // ---- REPEATED CURSOR (non-advancing later page) fails closed ----
    const repeatCursor = canonicalCursor(10)
    await servePages([
      { cursor: null, items: [item1], hasMore: true, nextCursor: repeatCursor },
      { cursor: repeatCursor, items: [item2], hasMore: true, nextCursor: repeatCursor },
    ])
    rec = makeRecipient()
    const beforeRepeat = await captureCounts()
    mountApp(rec)
    await runScan('repeated cursor')
    await waitNotice('incompleteIndex')
    const repeatCounts = await captureCounts()
    assert('repeated cursor: incomplete notice', bodyText().includes(t('incompleteIndex')))
    assert('repeated cursor: no CollageStage', !stageShown())
    assert('repeated cursor: zero content reads', repeatCounts.content === beforeRepeat.content)
    assert('repeated cursor: zero photo reads', repeatCounts.photo === beforeRepeat.photo)
    report.repeated = repeatCounts
    rootRef.current?.unmount()

    // ---- DUPLICATE CAPSULE ID ACROSS PAGES fails closed ----
    const duplicateCursor = canonicalCursor(11)
    await servePages([
      { cursor: null, items: [item1], hasMore: true, nextCursor: duplicateCursor },
      { cursor: duplicateCursor, items: [item1], hasMore: false, nextCursor: canonicalCursor(12) },
    ])
    rec = makeRecipient()
    const beforeDuplicate = await captureCounts()
    mountApp(rec)
    await runScan('duplicate id')
    await waitNotice('incompleteIndex')
    const duplicateCounts = await captureCounts()
    assert('duplicate id: incomplete notice', bodyText().includes(t('incompleteIndex')))
    assert('duplicate id: no CollageStage', !stageShown())
    assert('duplicate id: zero content reads', duplicateCounts.content === beforeDuplicate.content)
    assert('duplicate id: zero photo reads', duplicateCounts.photo === beforeDuplicate.photo)
    report.duplicate = duplicateCounts
    rootRef.current?.unmount()

    // ---- CROSS-PAGE MANY: same visual reference on two pages ----
    const manyPagesCursor = canonicalCursor(13)
    await servePages([
      { cursor: null, items: [item1], hasMore: true, nextCursor: manyPagesCursor },
      { cursor: manyPagesCursor, items: [item2], hasMore: false, nextCursor: canonicalCursor(14) },
    ])
    rec = makeRecipient()
    const beforeCrossMany = await captureCounts()
    mountApp(rec)
    await runScan('cross-page many')
    await waitNotice('ambiguousMatch')
    const crossManyCounts = await captureCounts()
    assert('cross-page many: ambiguous notice', bodyText().includes(t('ambiguousMatch')))
    assert('cross-page many: no CollageStage', !stageShown())
    assert('cross-page many: zero content reads', crossManyCounts.content === beforeCrossMany.content)
    assert('cross-page many: zero photo reads', crossManyCounts.photo === beforeCrossMany.photo)
    report.crossMany = crossManyCounts
    rootRef.current?.unmount()

    // ---- CROSS-PAGE UNIQUE: non-matching page then the matching page ----
    const crossCursor = canonicalCursor(15)
    await servePages([
      { cursor: null, items: [itemOther], hasMore: true, nextCursor: crossCursor },
      { cursor: crossCursor, items: [item1], hasMore: false, nextCursor: canonicalCursor(16) },
    ])
    rec = makeRecipient()
    const beforeCross = await captureCounts()
    const beforeCrossRequests = beforeCross.incoming?.length ?? 0
    mountApp(rec)
    await runScan('cross-page unique')
    await waitFor(() => rec.store.getSnapshot().phase === 'presentation-admitted', 'cross-page admitted', 60000)
    await waitFor(() => stageShown(), 'cross-page stage', 60000)
    const crossCounts = await captureCounts()
    const crossRequests = (crossCounts.incoming ?? []).slice(beforeCrossRequests)
    assert('cross-page: presentation admitted', rec.store.getSnapshot().phase === 'presentation-admitted')
    assert('cross-page: CollageStage displayed', stageShown())
    assert(
      'cross-page: ordered canonical cursor requests',
      JSON.stringify(crossRequests.map((request) => request.cursor)) === JSON.stringify([null, crossCursor]),
    )
    assert('cross-page: bounded page size 25', crossRequests.every((request) => request.limit === '25'))
    assert('cross-page: content read after unique match', crossCounts.content > beforeCross.content)
    assert('cross-page: photo read after unique match', crossCounts.photo > beforeCross.photo)
    report.crossPage = crossRequests
    rootRef.current?.unmount()

    // ---- LOCK ACROSS A DELAYED LATER PAGE: no late view/content ----
    const lockCursor = canonicalCursor(17)
    await servePages([
      { cursor: null, items: [item1], hasMore: true, nextCursor: lockCursor },
      { cursor: lockCursor, items: [item2], hasMore: false, nextCursor: canonicalCursor(18) },
    ])
    await postJson('/__delay', { cursor: lockCursor, ms: 1500 })
    rec = makeRecipient()
    const beforeLockPage = await captureCounts()
    mountApp(rec)
    await runScan('lock mid-page')
    await new Promise((resolve) => setTimeout(resolve, 250))
    rec.store.lock()
    await new Promise((resolve) => setTimeout(resolve, 2200))
    const lockPageCounts = await captureCounts()
    assert('lock mid-page: no late CollageStage', !stageShown())
    assert('lock mid-page: not admitted', rec.store.getSnapshot().phase !== 'presentation-admitted')
    assert('lock mid-page: zero content reads', lockPageCounts.content === beforeLockPage.content)
    assert('lock mid-page: zero photo reads', lockPageCounts.photo === beforeLockPage.photo)
    report.lockPage = lockPageCounts
    rootRef.current?.unmount()

    // ---- explicit unavailable override still refuses ----
    await serveCatalog([item1])
    rec = makeRecipient()
    const beforeUnavailable = await captureCounts()
    mountApp(rec, unavailableRecognition())
    await runScan('unavailable')
    await waitNotice('recognitionUnavailable')
    const unavailableCounts = await captureCounts()
    assert('unavailable override: recognitionUnavailable notice', bodyText().includes(t('recognitionUnavailable')))
    assert('unavailable override: no CollageStage', !stageShown())
    assert('unavailable override: zero content reads', unavailableCounts.content === beforeUnavailable.content)
    assert('unavailable override: zero photo reads', unavailableCounts.photo === beforeUnavailable.photo)
    rootRef.current?.unmount()

    // ---- lock during pending match: no late view ----
    await serveCatalog([item1])
    rec = makeRecipient()
    mountApp(rec)
    await runScan('lock pending')
    rec.store.lock()
    await new Promise((resolve) => setTimeout(resolve, 3000))
    assert('lock pending: no late CollageStage', !stageShown())
    assert('lock pending: phase not admitted', rec.store.getSnapshot().phase !== 'presentation-admitted')
    rootRef.current?.unmount()

    report.pass = failures.length === 0
  } catch (error) {
    assert('scan engine connected harness', false, String(error) + ' | body=' + bodyText().slice(-800))
  }
  ;(window as unknown as { __scanEngine?: unknown }).__scanEngine = { assertions, failures, report, notices }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
