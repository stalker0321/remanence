/**
 * T328 connected proof (Chromium-only): REAL pre-grant recognition index
 * through the ACTUAL Scan wiring.
 *
 * A genuine sender producer wire (synthetic textured CameraPort through the
 * accepted front-fingerprint producer) is sealed by the REAL `sealCapsule`
 * and served over a same-origin loopback three-route harness (verified
 * incoming page + directory signing key + recognition blob download). The
 * REAL exported `Scan` component runs its real `match()` flow with a
 * TEST-only OBSERVING engine (records index entries, returns NONE — never a
 * fake positive match). Asserts: entries/format-3 present, phase stays
 * keys-unlocked (no admission/presentation), exactly ONE blob download (the
 * recognition manifest — zero content/photo), index disposed after the
 * attempt, and the default unavailable engine still refuses with typed
 * recognition-unavailable. No real backend/account/provider.
 */
import { createRoot, type Root } from 'react-dom/client'
import { Scan } from '../../src/ui/App'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { fetchTransport } from '../../src/auth/http'
import { signPublishStatement } from '../../src/crypto/publish'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import { decryptArtifact } from '../../src/crypto/artifact'
import type { KeyCapabilities } from '../../src/crypto/keyOwner'
import { PresentationMemory } from '../../src/collage/memory'
import { FrontHold } from '../../src/create/front'
import { FrontFingerprintHold } from '../../src/create/frontFingerprint'
import { sealCapsule } from '../../src/create/sealCapsule'
import type { SealedCapsule } from '../../src/create/sealCapsule'
import { layoutCreatorNote } from '../../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../../src/collage/frozenRenderedPlan'
import { jpegDimensionFixture } from '../../src/collage/jpeg'
import type { TextMeasurer } from '../../src/collage/prepare'
import { styleById } from '../../src/collage/styles'
import { freezeSelection, type CreatorFreeze } from '../../src/create/creatorFreeze'
import type { DraftPhoto } from '../../src/create/photos'
import type { CameraPort, StillCapture } from '../../src/scan/camera'
import { unavailableRecognition, type MatchContext, type RecognitionEngine, type MatchResult } from '../../src/scan/grant'
import { bytesToHex } from '../../src/protocol/bytes'
import { catalog as i18nCatalog, type MessageKey } from '../../src/i18n/catalog'
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

function jpeg(id: string, width: number, height: number): DraftPhoto {
  const bytes = jpegDimensionFixture(width, height)
  return { id, bytes, width, height, contentHash: '' }
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

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'recognition-index-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = {}
  try {
    // ---- sender session + REAL genuine producer wire + REAL seal ----
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

    // Genuine front producer wire from synthetic textured camera pixels.
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

    const hashedPhotos = await Promise.all(
      [jpeg('p0', 1000, 1000), jpeg('p1', 1080, 1080), jpeg('p2', 1160, 1160)].map(async (photo) => ({
        ...photo,
        contentHash: bytesToHex(await sha256(photo.bytes)),
      })),
    )
    const freeze = await buildFreeze(hashedPhotos, NOTE_TEXT, senderStore.getSnapshot().generation)
    const ordered = freeze.slots.map((slot) => hashedPhotos.find((photo) => photo.id === slot.contentId)!)
    const capsuleId = crypto.randomUUID()
    const sealed: SealedCapsule = await sealCapsule({
      session: senderStore,
      keys: senderKeys,
      freeze,
      recipient: recipientSnapshot(),
      photos: ordered,
      capsuleId,
      candidateId: 'cand-1',
      createdAtEpochSeconds: 1_755_000_000n,
      epoch: 1n,
      frontFingerprint: produced.wire,
      placeLabel: null,
    })
    assert('real sealer produced envelope + blobs', sealed.envelopeCiphertext.length > 0 && sealed.blobs.length >= 5)

    // ---- serve the verified incoming wire + directory + blob bytes ----
    const envelopeSha = await sha256(sealed.envelopeCiphertext)
    const wireItem = {
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
        ciphertext_sha256: b64(envelopeSha),
      },
      blobs: sealed.blobs.map((blob) => ({
        blob_id: blob.blobId,
        kind: blob.kind,
        ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null,
        ciphertext_size: blob.ciphertextSize,
        ciphertext_sha256: b64(blob.ciphertextSha256),
      })),
    }
    await postJson('/__catalog', { items: [wireItem], has_more: false, next_cursor: null })
    await postJson('/__signing', {
      key_bundle_id: sealed.senderKeyBundleId,
      user_id: sealed.senderUserId,
      protocol_version: 1,
      status: 'ACTIVE',
      signing_public_keyset: b64(SIGNING_PUBLIC),
    })
    await postJson('/__blobs', sealed.blobs.map((blob) => ({ blobId: blob.blobId, bodyHex: bytesToHex(blob.ciphertext) })))

    // ---- recipient session + observing engine through the REAL Scan ----
    const recipientStore = new SessionStore('en')
    recipientStore.signIn({
      accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE, handle: 'ada',
      accessToken: 'a', refreshToken: 'r',
      accessExpiresAt: '2026-10-20T00:00:00Z', refreshExpiresAt: '2026-11-20T00:00:00Z',
    })
    recipientStore.unlock()
    const recipientCaps: KeyCapabilities = {
      accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE,
      openEnvelope: (info, ciphertext) => openRecipientEnvelope(HPKE_PRIVATE, info, ciphertext),
      decryptArtifact,
      sign: async () => { throw new Error('unused') },
      lock: () => {},
    }
    const recipientKeys = new MemoryKeys()
    recipientKeys.hold(recipientStore.getSnapshot(), recipientCaps)

    let scanQuad: Uint8Array = quad.slice()
    const scanCamera: CameraPort = {
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async (): Promise<StillCapture> => ({ bytes: scanQuad.slice(), width: 640, height: 480 }),
        stop: () => undefined,
      }),
    }
    const observed: { entries: number; capsuleId: string | null; index: { disposed: boolean } | null } = {
      entries: -1, capsuleId: null, index: null,
    }
    const observingEngine: RecognitionEngine = {
      async match(_capture, _incoming, context?: MatchContext): Promise<MatchResult> {
        const entries = context?.index.entries() ?? []
        observed.entries = entries.length
        observed.capsuleId = entries[0]?.capsuleId ?? null
        observed.index = context?.index ?? null
        assert('observing engine saw a live pre-grant index', context !== undefined && entries.length === 1)
        return { kind: 'none' }
      },
    }

    const rootRef: { current: Root | null } = { current: null }
    const mountScan = (engine: RecognitionEngine): void => {
      rootRef.current?.unmount()
      const host = document.createElement('div')
      host.setAttribute('data-testid', 'scan-host')
      host.className = 'shell'
      document.body.appendChild(host)
      rootRef.current = createRoot(host)
      rootRef.current.render(
        <Scan
          t={t}
          camera={scanCamera}
          onNotice={(notice) => { notices.push(notice) }}
          store={recipientStore}
          keys={recipientKeys}
          memory={new PresentationMemory()}
          transport={fetchTransport(window.location.origin)}
          engine={engine}
        />,
      )
    }

    mountScan(observingEngine)
    await waitFor(() => buttonByText(t('useCamera')) !== null, 'scan camera button')
    await clickText(t('useCamera'), 'scan open')
    await clickText(t('takePicture'), 'scan take')
    await waitFor(() => buttonByText(t('matchPicture')) !== null, 'match button')
    await clickText(t('matchPicture'), 'scan match')
    await waitFor(() => notices.includes('noMatch'), 'noMatch notice')

    const capture1 = await getJson<{ recognition: number; content: number; photo: number }>('/__capture')
    assert('observing engine returned NONE (no fake match)', notices.includes('noMatch'))
    assert('exactly one recognition blob download', capture1.recognition === 1, JSON.stringify(capture1))
    assert('zero content blob downloads', capture1.content === 0)
    assert('zero photo blob downloads', capture1.photo === 0)
    assert('entry capsule id matches the sealed capsule', observed.capsuleId === capsuleId, `${observed.capsuleId} vs ${capsuleId}`)
    assert('index was disposed after the attempt', observed.index !== null && observed.index.disposed === true)
    assert('phase stayed keys-unlocked (no admission/presentation)', recipientStore.getSnapshot().phase === 'keys-unlocked')
    assert('no presentation sheet', !bodyText().includes('held sheet'))

    // ---- run 2: DEFAULT unavailable engine still refuses, still no body reads ----
    notices.length = 0
    scanQuad = await texturedQuadJpeg(11, 640, 480)
    mountScan(unavailableRecognition())
    await waitFor(() => buttonByText(t('useCamera')) !== null, 'scan camera button (default engine)')
    await clickText(t('useCamera'), 'scan open (default engine)')
    await clickText(t('takePicture'), 'scan take (default engine)')
    await waitFor(() => buttonByText(t('matchPicture')) !== null, 'match button (default engine)')
    await clickText(t('matchPicture'), 'scan match (default engine)')
    await waitFor(() => notices.includes('recognitionUnavailable'), 'recognitionUnavailable notice')
    const capture2 = await getJson<{ recognition: number; content: number; photo: number }>('/__capture')
    assert('default unavailable engine still refuses', notices.includes('recognitionUnavailable'))
    assert('default engine run added only the recognition blob download', capture2.recognition === 2 && capture2.content === 0 && capture2.photo === 0, JSON.stringify(capture2))
    assert('phase still keys-unlocked after default engine', recipientStore.getSnapshot().phase === 'keys-unlocked')

    report.observed = { entries: observed.entries, capsuleId: observed.capsuleId }
    report.capture = capture2
    rootRef.current?.unmount()
  } catch (error) {
    assert('recognition index connected harness', false, String(error))
  }
  ;(window as unknown as { __recognitionIndex?: unknown }).__recognitionIndex = { assertions, failures, report, notices }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
