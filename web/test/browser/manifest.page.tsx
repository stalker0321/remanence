/**
 * T291 real sender→receiver crypto round-trip browser fixture (Chromium-only).
 *
 * Keeps the T259 ContentManifest coverage (mounted REAL CreatorComposer ->
 * frozen snapshot -> buildFrozenExpression -> encodeContentManifest ->
 * parseContentManifest -> prepare -> paintScene) and UPGRADES the photo inputs:
 * instead of fake hashes over two-tone canvases, the fixture now generates real
 * synthetic JPEGs and runs the REAL normalize pipeline (orientation/dims/SHA-256),
 * so the frozen content hashes match the actual normalized JPEG bytes.
 *
 * On top of that it joins the accepted sender primitives in ONE real-browser
 * round-trip: assembleSenderCapsule encodes the actual RecognitionManifest v2 +
 * GENEX04 ContentManifest + AEAD of recognition/content/normalized photos under
 * the exact artifact AADs, builds the exact PublishStatement, signs it with the
 * fixed Ed25519 golden, binds the raw statement SHA-256 in the envelope
 * plaintext, and real-HPKE-seals it. A scripted (non-network) ApiTransport /
 * ByteTransport then feeds the REAL fetchIncoming + downloadCiphertext, a
 * test-issued ScanGrant and MemoryKeys drive the UNCHANGED openGrantedPostcard,
 * and the receiver is painted from the DECRYPTED returned JPEGs (never the
 * original canvases). This is a synthetic TEST fixture only: no production
 * grant/admission/recognition/clock/publish path and no real media.
 */
import { createRoot, type Root } from 'react-dom/client'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import type { ApiRequest, ApiResult, ApiTransport, ByteRequest, ByteResult, ByteTransport } from '../../src/auth/http'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { projectionHash } from '../../src/collage/codec'
import { buildFrozenExpression } from '../../src/collage/frozenExpression'
import { encodeContentManifest } from '../../src/collage/manifest'
import { canvasTextMeasurer } from '../../src/collage/measure'
import { openGrantedPostcard } from '../../src/collage/open'
import { paintScene, type PaintImage } from '../../src/collage/paint'
import { prepare, type Scene } from '../../src/collage/prepare'
import { parseContentManifest } from '../../src/collage/wire'
import { decryptArtifact } from '../../src/crypto/artifact'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import type { KeyCapabilities } from '../../src/crypto/keyOwner'
import { browserPhotoCodec } from '../../src/create/browserPhoto'
import { PhotoNormalizer, type NormalizedPhoto } from '../../src/create/normalize'
import type { CreatorFreeze } from '../../src/create/creatorFreeze'
import { arrayBuffer } from '../../src/protocol/bytes'
import { uuidToBytes } from '../../src/protocol/uuid'
import { ScanGrant } from '../../src/scan/grant'
import { SessionStore } from '../../src/session/session'
import { SyncError } from '../../src/sync/errors'
import { downloadCiphertext, fetchIncoming, type IncomingCapsule } from '../../src/sync/incoming'
import { signPublishStatement } from '../../src/crypto/publish'
import type { DraftPhoto } from '../../src/create/photos'
import type { RecipientSnapshot } from '../../src/create/recipient'
import { sealCapsule, type SealedCapsule } from '../../src/create/sealCapsule'
import { assembleSenderCapsule, deriveCapsuleBlobId } from '../senderRoundTrip'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import './composerFonts.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

/** BigInt-safe serialization for assertion details (the epoch is a bigint). */
function ser(value: unknown): string {
  return JSON.stringify(value, (_key, item) => (typeof item === 'bigint' ? `${item}n` : item))
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function sha256B64(bytes: Uint8Array): Promise<string> {
  return b64(new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes))))
}

// Distinct explicit synthetic sender/recipient identities (NOT a real account).
const SENDER_ID = '11111111-2222-4333-8444-555555555555'
const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const CAPSULE_UUID = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
const CAPSULE = uuidToBytes(CAPSULE_UUID)
const SENDER_HANDLE = 'sergio'
const RECIPIENT_HANDLE = 'ada'
const RECIPIENT = { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: '0'.repeat(16), handle: RECIPIENT_HANDLE }
const EPOCH = 17n
const CREATED_AT = 1_755_000_000n
const CANDIDATE = 'cand-1'
/** Explicit SYNTHETIC opaque "fingerprint". NOT a real FRONT recognition result. */
const RECOGNITION_FP = Uint8Array.from({ length: 48 }, (_value, index) => (index * 7 + 1) & 0xff)

// Fixed committed golden keysets (TEST-ONLY); not a real account/provider unlock.
const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
const HPKE_PRIVATE = keysetFromJson(hpkeVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

/** Explicit test-only confirmed recipient (fixed HPKE golden public half). */
const RECIPIENT_SNAPSHOT: RecipientSnapshot = {
  userId: RECIPIENT_ID,
  handle: RECIPIENT_HANDLE,
  keyBundleId: RECIPIENT_BUNDLE,
  suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
  protocolVersion: 1,
  encryptionPublicKeyset: b64(HPKE_PUBLIC),
  signingPublicKeyset: b64(SIGNING_PUBLIC),
  directoryVersion: '0'.repeat(16),
  self: false,
}

const SHORT = 'Probe note text.'
const MULTILINE = 'A small note that wraps across a few rows so the receiver must reproduce every single word.'
/** Distinct from SHORT so the composer invalidates the prior held freeze. */
const CRYPTO_SHORT = 'Sealed round-trip note.'
/** Distinct from the prior crypto case so the composer invalidates. */
const CRYPTO_SEAL_NOTE = 'Production sealed note.'

/** Three visibly and byte-distinct synthetic patterns (unique normalized hashes). */
const PATTERN_COLORS: ReadonlyArray<readonly [string, string]> = [
  ['#c0392b', '#f1c40f'],
  ['#1e8449', '#8e44ad'],
  ['#2471a3', '#e67e22'],
]

function twoTone(width: number, height: number, variant: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const [left, right] = PATTERN_COLORS[variant % PATTERN_COLORS.length]!
  context.fillStyle = left
  context.fillRect(0, 0, width / 2, height)
  context.fillStyle = right
  context.fillRect(width / 2, 0, width / 2, height)
  return canvas
}

function canvasJpeg(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new Error('jpeg encode failed'))
        return
      }
      void blob.arrayBuffer().then((buffer) => resolve(new Uint8Array(buffer)), reject)
    }, 'image/jpeg', 0.92)
  })
}

interface BuiltPhotos {
  photos: CreatorComposerPhoto[]
  paint: Map<string, PaintImage>
  bytes: Map<string, Uint8Array>
  bitmaps: ImageBitmap[]
}

/**
 * Real synthetic JPEGs -> the REAL normalize pipeline. The composer hash input
 * is the SHA-256 of the normalized JPEG, so freeze hashes match the encrypted
 * photo bytes. Owned normalized bytes/bitmaps are returned for teardown.
 */
async function buildPhotos(): Promise<BuiltPhotos> {
  const normalizer = new PhotoNormalizer(browserPhotoCodec(document))
  const photos: CreatorComposerPhoto[] = []
  const paint = new Map<string, PaintImage>()
  const bytes = new Map<string, Uint8Array>()
  const bitmaps: ImageBitmap[] = []
  for (let index = 0; index < 3; index += 1) {
    const source = await canvasJpeg(twoTone(800, 800, index))
    const outcome = await normalizer.normalize(source)
    source.fill(0)
    if (!outcome.ok) throw new Error(`normalize ${index} refused: ${outcome.reason}`)
    const photo: NormalizedPhoto = outcome.photo
    const contentId = `p${index}`
    bytes.set(contentId, photo.bytes)
    const bitmap = await createImageBitmap(new Blob([arrayBuffer(photo.bytes)], { type: 'image/jpeg' }))
    bitmaps.push(bitmap)
    photos.push({ contentId, width: photo.width, height: photo.height, contentHash: photo.contentHash, image: bitmap })
    paint.set(contentId, { source: bitmap, width: photo.width, height: photo.height })
  }
  return { photos, paint, bytes, bitmaps }
}

interface FontEvidence {
  registered: Array<{ family: string; status: string }>
  studySansLoaded: number
  studySansWidth: number
  missingWidth: number
  fontRequests: Array<{ name: string; status: number }>
}

async function collectFontEvidence(): Promise<FontEvidence> {
  const sans = await document.fonts.load('normal 400 16px StudySans')
  await document.fonts.load('normal 400 27px StudySerif')
  await document.fonts.load('italic 400 20px StudySerif')
  await document.fonts.ready
  const probe = document.createElement('canvas').getContext('2d')
  const widthOf = (font: string): number => {
    if (!probe) return -1
    probe.font = font
    return probe.measureText('Wg').width
  }
  const fontRequests = performance
    .getEntriesByType('resource')
    .filter((entry) => entry.name.includes('/fonts/'))
    .map((entry) => {
      const resource = entry as PerformanceResourceTiming & { responseStatus?: number }
      return { name: resource.name.replace(location.origin, ''), status: resource.responseStatus ?? 0 }
    })
  return {
    registered: Array.from(document.fonts).map((face) => ({ family: face.family, status: face.status })),
    studySansLoaded: sans.length,
    studySansWidth: widthOf(`normal 400 16px StudySans`),
    missingWidth: widthOf(`normal 400 16px __remanence_missing_family__`),
    fontRequests,
  }
}

interface Traces {
  fillText: Array<{ text: string; x: number; y: number; font: string }>
  drawImage: Array<{ dx: number; dy: number; dw: number; dh: number }>
}

function installTraces(): Traces {
  const fillText: Array<{ text: string; x: number; y: number; font: string }> = []
  const drawImage: Array<{ dx: number; dy: number; dw: number; dh: number }> = []
  const proto = CanvasRenderingContext2D.prototype as unknown as {
    fillText: (this: CanvasRenderingContext2D, text: string, x: number, y: number) => void
    drawImage: (...args: unknown[]) => void
  }
  const realFill = proto.fillText
  proto.fillText = function (this: CanvasRenderingContext2D, text: string, x: number, y: number): void {
    fillText.push({ text, x, y, font: this.font })
    realFill.call(this, text, x, y)
  }
  const realImage = proto.drawImage
  proto.drawImage = function (this: CanvasRenderingContext2D, ...args: unknown[]): void {
    if (args.length >= 9) drawImage.push({ dx: args[5] as number, dy: args[6] as number, dw: args[7] as number, dh: args[8] as number })
    ;(realImage as (...values: unknown[]) => void).apply(this, args)
  }
  return { fillText, drawImage }
}

function App(props: {
  note: string | null
  session: SessionStore
  measurer: ReturnType<typeof canvasTextMeasurer>
  photos: CreatorComposerPhoto[]
  onFrozenChange: (frozen: CreatorFreeze | null) => void
}): React.JSX.Element {
  return (
    <CreatorComposer
      photos={props.photos}
      note={props.note}
      musicSelected={false}
      styleId="press"
      recipient={RECIPIENT}
      ownerId={SENDER_ID}
      session={props.session}
      lang="en"
      measurer={props.measurer}
      onFrozenChange={props.onFrozenChange}
    />
  )
}

/** Real receiver capabilities over the fixed HPKE private golden + real AEAD. */
function receiverCapabilities(): KeyCapabilities {
  return {
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    openEnvelope: (info, ciphertext) => openRecipientEnvelope(HPKE_PRIVATE, info, ciphertext),
    decryptArtifact: (keyset, aad, ciphertext, kind) => decryptArtifact(keyset, aad, ciphertext, kind),
    sign: async () => {
      throw new Error('sign is unused by openGrantedPostcard')
    },
    lock: () => {},
  }
}

interface SenderCapsuleResult {
  capsule: IncomingCapsule
  statement: Uint8Array
  signature: Uint8Array
  envelopeCiphertext: Uint8Array
  content: { blobId: string; ciphertext: Uint8Array }
  photos: Array<{ contentId: string; ordinal: number; blobId: string; ciphertext: Uint8Array }>
}

/** In-process scripted transports: no network, no backend, no provider. */
function scriptedTransport(result: SenderCapsuleResult, envelopeShaB64: string, corrupt?: { blobId: string; bytes: Uint8Array }): ApiTransport & ByteTransport {
  const byBlob = new Map<string, Uint8Array>([
    [result.content.blobId, result.content.ciphertext],
    ...result.photos.map((photo): [string, Uint8Array] => [photo.blobId, photo.ciphertext]),
  ])
  const wireBlobs = result.capsule.blobs.map((blob) => ({
    blob_id: blob.blobId,
    kind: blob.kind,
    ordinal: blob.ordinal,
    ciphertext_size: blob.ciphertextSize,
    ciphertext_sha256: blob.ciphertextSha256,
  }))
  const page = {
    items: [
      {
        capsule_id: result.capsule.capsuleId,
        sender_user_id: result.capsule.senderUserId,
        recipient_user_id: result.capsule.recipientUserId,
        sender_key_bundle_id: result.capsule.senderKeyBundleId,
        recipient_key_bundle_id: result.capsule.recipientKeyBundleId,
        protocol_version: 1,
        ready_at: '2023-11-14T22:13:20Z',
        signed_publish_statement: {
          statement: b64(result.statement),
          statement_sha256: b64(result.capsule.statementSha256),
          signature: b64(result.signature),
        },
        recipient_envelope: {
          recipient_key_bundle_id: result.capsule.recipientKeyBundleId,
          ciphertext: b64(result.envelopeCiphertext),
          ciphertext_size: result.envelopeCiphertext.length,
          ciphertext_sha256: envelopeShaB64,
        },
        blobs: wireBlobs,
      },
    ],
    has_more: false,
    next_cursor: null,
  }
  const directory = {
    key_bundle_id: SENDER_BUNDLE,
    user_id: SENDER_ID,
    suite: 'tink',
    protocol_version: 1,
    encryption_public_keyset: b64(SIGNING_PUBLIC),
    signing_public_keyset: b64(SIGNING_PUBLIC),
    status: 'ACTIVE',
    created_at: '2023-11-14T22:13:20Z',
  }
  return {
    async request(input: ApiRequest): Promise<ApiResult> {
      if (input.path === '/v1/capsules/incoming') return { status: 200, body: page }
      return { status: 200, body: directory }
    },
    async requestBytes(input: ByteRequest): Promise<ByteResult> {
      const blobId = input.path.slice(input.path.lastIndexOf('/') + 1)
      if (corrupt && corrupt.blobId === blobId) return { status: 200, body: corrupt.bytes }
      const body = byBlob.get(blobId)
      return body ? { status: 200, body } : { status: 404, body: new Uint8Array() }
    },
  }
}

function settledSession(): { session: SessionStore; keys: MemoryKeys } {
  const session = new SessionStore('en')
  session.signIn({
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    handle: RECIPIENT_HANDLE,
    accessToken: 'receiver-access-token',
    refreshToken: 'receiver-refresh-token',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  session.matchPostcard()
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), receiverCapabilities())
  return { session, keys }
}

/** TEST-ONLY sender capability whose `sign` uses the committed golden keyset. */
function senderKeys(session: SessionStore): MemoryKeys {
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), {
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    openEnvelope: async () => {
      throw new Error('unused by sealCapsule')
    },
    decryptArtifact: async () => {
      throw new Error('unused by sealCapsule')
    },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => {},
  })
  return keys
}

/** Scripted transports over a production SealedCapsule (no network/backend). */
function sealedTransport(sealed: SealedCapsule, envelopeShaB64: string): ApiTransport & ByteTransport {
  const byBlob = new Map<string, Uint8Array>(sealed.blobs.map((blob): [string, Uint8Array] => [blob.blobId, blob.ciphertext]))
  const page = {
    items: [
      {
        capsule_id: sealed.capsuleId,
        sender_user_id: sealed.senderUserId,
        recipient_user_id: sealed.recipientUserId,
        sender_key_bundle_id: sealed.senderKeyBundleId,
        recipient_key_bundle_id: sealed.recipientKeyBundleId,
        protocol_version: 1,
        ready_at: '2023-11-14T22:13:20Z',
        signed_publish_statement: { statement: b64(sealed.statement), statement_sha256: b64(sealed.statementSha256), signature: b64(sealed.signature) },
        recipient_envelope: {
          recipient_key_bundle_id: sealed.recipientKeyBundleId,
          ciphertext: b64(sealed.envelopeCiphertext),
          ciphertext_size: sealed.envelopeCiphertext.length,
          ciphertext_sha256: envelopeShaB64,
        },
        blobs: sealed.blobs.map((blob) => ({ blob_id: blob.blobId, kind: blob.kind, ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null, ciphertext_size: blob.ciphertextSize, ciphertext_sha256: b64(blob.ciphertextSha256) })),
      },
    ],
    has_more: false,
    next_cursor: null,
  }
  const directory = {
    key_bundle_id: SENDER_BUNDLE,
    user_id: SENDER_ID,
    suite: 'tink',
    protocol_version: 1,
    encryption_public_keyset: b64(SIGNING_PUBLIC),
    signing_public_keyset: b64(SIGNING_PUBLIC),
    status: 'ACTIVE',
    created_at: '2023-11-14T22:13:20Z',
  }
  return {
    async request(input: ApiRequest): Promise<ApiResult> {
      if (input.path === '/v1/capsules/incoming') return { status: 200, body: page }
      return { status: 200, body: directory }
    },
    async requestBytes(input: ByteRequest): Promise<ByteResult> {
      const blobId = input.path.slice(input.path.lastIndexOf('/') + 1)
      const body = byBlob.get(blobId)
      return body ? { status: 200, body } : { status: 404, body: new Uint8Array() }
    },
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'manifest-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const sheet = document.createElement('canvas')
  sheet.width = 360
  sheet.height = 640
  sheet.id = 'manifest-sheet'
  document.body.appendChild(sheet)
  const receiverContext = sheet.getContext('2d')
  if (!receiverContext) throw new Error('no receiver 2d context')
  const rootRef: { root: Root | null } = { root: null }
  let fonts: FontEvidence | null = null
  let built: BuiltPhotos | null = null
  try {
    built = await buildPhotos()
    const photos = built.photos
    const paintImages = built.paint
    const normalizedByContentId = built.bytes
    const blobIdByContentId = new Map<string, Uint8Array>(photos.map((photo, index): [string, Uint8Array] => [photo.contentId, uuidToBytes(deriveCapsuleBlobId(CAPSULE_UUID, 0x10 + index))]))
    const session = new SessionStore('en')
    session.signIn({
      accountId: SENDER_ID,
      bundleId: SENDER_BUNDLE,
      handle: SENDER_HANDLE,
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    const measurer = canvasTextMeasurer(document)
    const host = document.createElement('div')
    host.setAttribute('data-testid', 't259-host')
    document.body.appendChild(host)
    rootRef.root = createRoot(host)
    const frozenLog: Array<CreatorFreeze | null> = []
    const render = (note: string | null): void => {
      rootRef.root!.render(
        <App note={note} session={session} measurer={measurer} photos={photos} onFrozenChange={(frozen) => frozenLog.push(frozen)} />,
      )
    }
    await new Promise((resolve) => setTimeout(resolve, 0))
    fonts = await collectFontEvidence()
    assert('bundled faces registered', fonts.registered.length > 0, JSON.stringify(fonts.registered))
    assert('StudySans loaded', fonts.studySansLoaded >= 1 && fonts.registered.some((face) => face.family === 'StudySans' && face.status === 'loaded'), JSON.stringify(fonts))
    assert('bundled /fonts fetched over the wire', fonts.fontRequests.some((r) => r.status >= 200 && r.status < 400), JSON.stringify(fonts.fontRequests))
    assert('real face differs from a missing-family fallback', fonts.studySansWidth > 0 && fonts.studySansWidth !== fonts.missingWidth, `sans=${fonts.studySansWidth} missing=${fonts.missingWidth}`)

    const traces = installTraces()
    const waitFor = async (predicate: () => boolean, label: string): Promise<void> => {
      const deadline = Date.now() + 30000
      for (;;) {
        let done = false
        try { done = predicate() } catch { done = false }
        if (done) return
        if (Date.now() > deadline) throw new Error(`timeout waiting for ${label}`)
        await new Promise((resolve) => setTimeout(resolve, 50))
      }
    }
    const freezeButton = (): HTMLButtonElement | null => document.querySelector('[data-testid="creator-freeze"]')
    const freezeEnabled = (): boolean => {
      const button = freezeButton()
      return button !== null && !button.disabled
    }
    const armAndFreeze = async (label: string, note: string | null): Promise<CreatorFreeze | null> => {
      const start = frozenLog.length
      const heldBefore = frozenLog.length > 0 && frozenLog[frozenLog.length - 1] !== null
      render(note)
      if (heldBefore) {
        await waitFor(() => frozenLog.length > start && frozenLog[frozenLog.length - 1] === null, `${label} invalidated`)
      }
      await waitFor(freezeEnabled, `${label} freeze armed`)
      freezeButton()!.click()
      await waitFor(() => frozenLog.length > start && frozenLog[frozenLog.length - 1] !== null, `${label} frozen`)
      return frozenLog[frozenLog.length - 1] ?? null
    }

    const runCase = async (label: string, note: string | null): Promise<void> => {
      const snapshot = await armAndFreeze(label, note)
      assert(`${label}: real mounted freeze snapshot`, snapshot !== null && snapshot.renderedKey.length > 0, String(snapshot))
      if (!snapshot) return
      const builtExpression = await buildFrozenExpression(snapshot, EPOCH)
      assert(`${label}: adapter produced expression`, builtExpression.ok, ser(builtExpression))
      if (!builtExpression.ok) return
      const plan = snapshot.renderedPlan

      const manifest = await encodeContentManifest({ expression: builtExpression.expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId })
      assert(`${label}: manifest produced`, manifest.ok, ser(manifest))
      if (!manifest.ok) return
      const parsed = await parseContentManifest(manifest.bytes)
      assert(`${label}: parser capsule/candidate/hash bind`, parsed.capsuleId.join(',') === Array.from(CAPSULE).join(',') && parsed.candidateId === CANDIDATE && parsed.expressionHash === (await projectionHash(builtExpression.expression, CANDIDATE, blobIdByContentId)), JSON.stringify({ capsule: Array.from(parsed.capsuleId), candidate: parsed.candidateId }))
      const manifestRegion = parsed.expression.noteRegion
      const planRegion = plan.note?.region ?? null
      assert(
        `${label}: no sidecar — note region carried by the manifest`,
        JSON.stringify(manifestRegion) === JSON.stringify(planRegion) && (plan.note === null) === (manifestRegion === null),
        JSON.stringify(manifestRegion),
      )
      assert(`${label}: parser placeholders only (owner/epoch)`, parsed.expression.input.ownerId === 'receiver' && parsed.expression.input.epoch === 0n)

      parsed.expression.placements.forEach((placement, index) => {
        const frozenSlot = plan.slots[index]!
        assert(
          `${label}: placement ${index} = frozen integer destination/shape`,
          placement.x === frozenSlot.destination.x &&
            placement.y === frozenSlot.destination.y &&
            placement.width === frozenSlot.destination.width &&
            placement.height === frozenSlot.destination.height &&
            placement.shape === frozenSlot.shape &&
            placement.crop!.x === snapshot.slots[index]!.crop.x,
          JSON.stringify(placement),
        )
      })

      const sources = new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }]))
      const prepared = await prepare(parsed.expression, parsed.note, null, sources, measurer)
      assert(`${label}: receiver prepare ready (no sidecar)`, prepared.kind === 'ready', ser(prepared))
      if (prepared.kind !== 'ready') return
      const scene = prepared.scene as Scene
      scene.photos.forEach((photo, index) => {
        const frozenSlot = plan.slots[index]!
        assert(
          `${label}: receiver photo ${index} source/destination match the captured preview`,
          photo.destination.x === frozenSlot.destination.x &&
            photo.destination.y === frozenSlot.destination.y &&
            photo.destination.width === frozenSlot.destination.width &&
            photo.destination.height === frozenSlot.destination.height &&
            photo.source.x === frozenSlot.source.x &&
            photo.source.y === frozenSlot.source.y &&
            photo.source.width === frozenSlot.source.width &&
            photo.source.height === frozenSlot.source.height,
          JSON.stringify({ got: photo, want: frozenSlot }),
        )
      })

      traces.fillText.length = 0
      traces.drawImage.length = 0
      assert(`${label}: receiver paints`, paintScene(receiverContext, scene, paintImages, null))
      const paintedDestination = traces.drawImage.slice(-plan.slots.length).map((call) => [call.dx, call.dy, call.dw, call.dh])
      assert(
        `${label}: receiver drawImage destination = frozen slots`,
        JSON.stringify(paintedDestination) === JSON.stringify(plan.slots.map((slot) => [slot.destination.x, slot.destination.y, slot.destination.width, slot.destination.height])),
        JSON.stringify(paintedDestination),
      )

      const noteCalls = traces.fillText.filter((call) => call.font.includes('StudySans'))
      if (plan.note === null) {
        assert(`${label}: absent note paints no rows`, noteCalls.length === 0, JSON.stringify(noteCalls))
      } else {
        const paintedRows = noteCalls.map((call) => ({ text: call.text, x: call.x, baseline: call.y }))
        const expectedRows = plan.note.rows.map((row) => ({ text: row.text, x: row.x, baseline: row.baseline }))
        assert(`${label}: receiver paints every captured note row/word/baseline`, JSON.stringify(paintedRows) === JSON.stringify(expectedRows), JSON.stringify({ paintedRows, expectedRows }))
        const words = noteCalls.map((call) => call.text).join(' ').split(/\s+/).filter((word) => word.length > 0)
        assert(`${label}: every note word present`, JSON.stringify(words) === JSON.stringify((snapshot.note ?? '').split(/\s+/).filter((word) => word.length > 0)), JSON.stringify(words))
      }
      const provenanceTexts = traces.fillText.filter((call) => call.font.includes('StudySerif')).map((call) => call.text)
      assert(`${label}: receiver paints the frozen provenance text`, plan.provenance.every((mark) => provenanceTexts.includes(mark.text)), JSON.stringify(provenanceTexts))
    }

    await runCase('short note', SHORT)
    await runCase('multiline note', MULTILINE)
    await runCase('absent note', null)

    // Typed negatives against a real frozen snapshot.
    const control = await armAndFreeze('control', SHORT)
    assert('control: real mounted freeze snapshot', control !== null, String(control))
    if (control) {
      const builtExpression = await buildFrozenExpression(control, EPOCH)
      if (builtExpression.ok) {
        const badCapsule = await encodeContentManifest({ expression: builtExpression.expression, capsuleId: new Uint8Array(15), candidateId: CANDIDATE, blobIdByContentId })
        assert('short capsule id refuses typed', !badCapsule.ok && badCapsule.reason === 'capsule-id', ser(badCapsule))
        const missing = new Map(blobIdByContentId)
        missing.delete('p1')
        const badBlob = await encodeContentManifest({ expression: builtExpression.expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: missing })
        assert('missing blob id refuses typed', !badBlob.ok && badBlob.reason === 'blob-map', ser(badBlob))
        const fractional = structuredClone(builtExpression.expression)
        fractional.placements[0]!.x += 0.5
        const badFraction = await encodeContentManifest({ expression: fractional, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId })
        assert('fractional wire geometry refuses typed', !badFraction.ok && badFraction.reason === 'fraction', ser(badFraction))
      }
    }

    // Deterministic bytes on the live frozen snapshot.
    if (control) {
      const expression = await buildFrozenExpression(control, EPOCH)
      if (expression.ok) {
        const runA = await encodeContentManifest({ expression: expression.expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId })
        const runB = await encodeContentManifest({ expression: expression.expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId })
        assert('manifest bytes deterministic on the live snapshot', runA.ok && runB.ok && runA.bytes.join(',') === runB.bytes.join(','))
      }
    }

    // ---- Real whole-crypto sender -> receiver round-trip ----
    const assembleFor = async (snapshot: CreatorFreeze): Promise<Awaited<ReturnType<typeof assembleSenderCapsule>>> => {
      const builtExpression = await buildFrozenExpression(snapshot, EPOCH)
      if (!builtExpression.ok) throw new Error(`adapter refused: ${builtExpression.reason}`)
      return assembleSenderCapsule({
        capsuleId: CAPSULE_UUID,
        senderUserId: SENDER_ID,
        recipientUserId: RECIPIENT_ID,
        senderKeyBundleId: SENDER_BUNDLE,
        recipientKeyBundleId: RECIPIENT_BUNDLE,
        senderHandle: SENDER_HANDLE,
        createdAtEpochSeconds: CREATED_AT,
        candidateId: CANDIDATE,
        expression: builtExpression.expression,
        photoBytesByContentId: normalizedByContentId,
        recognitionFingerprint: RECOGNITION_FP,
        senderSigningPrivateKeyset: SIGNING_PRIVATE,
        recipientHpkePublicKeyset: HPKE_PUBLIC,
      })
    }

    const runCryptoRoundTrip = async (label: string, note: string | null): Promise<void> => {
      const snapshot = await armAndFreeze(label, note)
      assert(`${label}: crypto freeze snapshot`, snapshot !== null, String(snapshot))
      if (!snapshot) return
      const plan = snapshot.renderedPlan
      const result = await assembleFor(snapshot)
      let recognitionPlain: Uint8Array | null = null
      let opened: Awaited<ReturnType<typeof openGrantedPostcard>> | null = null
      let receiverKeys: MemoryKeys | null = null
      try {
        // The recognition artifact is not decoded by production open, so prove it
        // really decrypts to the exact encoded plaintext (real AEAD + real AAD).
        recognitionPlain = await decryptArtifact(result.capsuleKeyset, result.recognition.aad, result.recognition.ciphertext, 'RECOGNITION_MANIFEST')
        assert(`${label}: recognition artifact real-decrypts to the encoded plaintext`, sameBytes(recognitionPlain, result.recognition.plaintext))
        assert(`${label}: recognition blob id is the capsule byte0 tag`, result.recognition.blobId === deriveCapsuleBlobId(CAPSULE_UUID, 0x01))

        const { session: rxSession, keys } = settledSession()
        receiverKeys = keys
        const envelopeShaB64 = await sha256B64(result.envelopeCiphertext)
        const transport = scriptedTransport(result, envelopeShaB64)
        const catalog = await fetchIncoming(rxSession, transport)
        assert(`${label}: fetchIncoming returns one verified item`, catalog.items.length === 1, String(catalog.items.length))
        const item = catalog.items[0]
        if (!item) return
        assert(`${label}: fetchIncoming bound the exact raw statement hash`, sameBytes(item.statementSha256, result.capsule.statementSha256))
        assert(`${label}: fetchIncoming returns the sealed envelope`, sameBytes(item.envelopeCiphertext, result.envelopeCiphertext))

        const contentCiphertext = await downloadCiphertext(rxSession, transport, item, result.content.blobId)
        assert(`${label}: downloadCiphertext content hash guard passes`, sameBytes(contentCiphertext, result.content.ciphertext))
        const sealedPhotos: Array<{ blobId: string; ordinal: number; ciphertext: Uint8Array }> = []
        for (const photo of result.photos) {
          const ciphertext = await downloadCiphertext(rxSession, transport, item, photo.blobId)
          assert(`${label}: downloadCiphertext photo ${photo.ordinal} hash guard passes`, sameBytes(ciphertext, photo.ciphertext))
          sealedPhotos.push({ blobId: photo.blobId, ordinal: photo.ordinal, ciphertext })
        }

        const grant = ScanGrant.issue(RECIPIENT_ID, CAPSULE_UUID)
        opened = await openGrantedPostcard(rxSession, keys, grant, item, { blobId: result.content.blobId, ciphertext: contentCiphertext }, sealedPhotos, measurer)
        for (const photo of result.photos) {
          const returned = opened.photos.get(photo.contentId)
          const expected = normalizedByContentId.get(photo.contentId)!
          assert(`${label}: returned decrypted JPEG ${photo.ordinal} is byte-identical to the normalized input`, returned !== undefined && sameBytes(returned, expected))
        }

        // Paint the receiver from the DECRYPTED JPEGs (never the original sources).
        const openedScene = opened.scene as Scene
        assert(`${label}: receiver scene has the authored photo count`, openedScene.photos.length === photos.length)
        const decryptedImages = new Map<string, PaintImage>()
        const decryptedBitmaps: ImageBitmap[] = []
        try {
          for (const scenePhoto of openedScene.photos) {
            const jpeg = opened.photos.get(scenePhoto.contentId)
            if (!jpeg) throw new Error(`missing decrypted photo ${scenePhoto.contentId}`)
            const bitmap = await createImageBitmap(new Blob([arrayBuffer(jpeg)], { type: 'image/jpeg' }))
            decryptedBitmaps.push(bitmap)
            decryptedImages.set(scenePhoto.contentId, { source: bitmap, width: bitmap.width, height: bitmap.height })
          }
          traces.fillText.length = 0
          traces.drawImage.length = 0
          assert(`${label}: receiver paints from decrypted JPEGs`, paintScene(receiverContext, openedScene, decryptedImages, null))
          const destinations = traces.drawImage.slice(-plan.slots.length).map((call) => [call.dx, call.dy, call.dw, call.dh])
          assert(
            `${label}: decrypted-paint drawImage destination = frozen slots`,
            JSON.stringify(destinations) === JSON.stringify(plan.slots.map((slot) => [slot.destination.x, slot.destination.y, slot.destination.width, slot.destination.height])),
            JSON.stringify(destinations),
          )
          openedScene.photos.forEach((scenePhoto, index) => {
            const frozenSlot = plan.slots[index]!
            assert(
              `${label}: decrypted scene photo ${index} source/destination/crop = frozen preview`,
              scenePhoto.destination.x === frozenSlot.destination.x &&
                scenePhoto.destination.y === frozenSlot.destination.y &&
                scenePhoto.destination.width === frozenSlot.destination.width &&
                scenePhoto.destination.height === frozenSlot.destination.height &&
                scenePhoto.source.x === frozenSlot.source.x &&
                scenePhoto.source.y === frozenSlot.source.y &&
                scenePhoto.source.width === frozenSlot.source.width &&
                scenePhoto.source.height === frozenSlot.source.height &&
                scenePhoto.shape === frozenSlot.shape,
              JSON.stringify({ got: scenePhoto, want: frozenSlot }),
            )
          })
          assert(`${label}: decrypted scene style = frozen style`, openedScene.styleId === snapshot.styleId, `${openedScene.styleId} vs ${snapshot.styleId}`)
          const noteCalls = traces.fillText.filter((call) => call.font.includes('StudySans'))
          if (plan.note === null) {
            assert(`${label}: decrypted-paint absent note paints no rows`, noteCalls.length === 0, JSON.stringify(noteCalls))
          } else {
            const paintedRows = noteCalls.map((call) => ({ text: call.text, x: call.x, baseline: call.y }))
            const expectedRows = plan.note.rows.map((row) => ({ text: row.text, x: row.x, baseline: row.baseline }))
            assert(`${label}: decrypted-paint note rows/words/baselines = captured preview`, JSON.stringify(paintedRows) === JSON.stringify(expectedRows), JSON.stringify({ paintedRows, expectedRows }))
          }
          const provenanceTexts = traces.fillText.filter((call) => call.font.includes('StudySerif')).map((call) => call.text)
          assert(`${label}: decrypted-paint provenance = captured preview`, plan.provenance.every((mark) => provenanceTexts.includes(mark.text)), JSON.stringify(provenanceTexts))
        } finally {
          for (const bitmap of decryptedBitmaps) bitmap.close()
        }

        // Bounded negatives against the same assembled result.
        const corrupted = result.photos[0]!
        const corruptBytes = corrupted.ciphertext.slice()
        corruptBytes[Math.floor(corruptBytes.length / 2)] ^= 0x01
        let corruptRejected = false
        try {
          await downloadCiphertext(rxSession, scriptedTransport(result, envelopeShaB64, { blobId: corrupted.blobId, bytes: corruptBytes }), item, corrupted.blobId)
        } catch (error) {
          corruptRejected = error instanceof SyncError && error.code === 'HASH_MISMATCH'
        }
        assert(`${label}: corrupt download body rejected by the real hash guard`, corruptRejected)

        let wrongRecipientRejected = false
        try {
          const wrong = scriptedTransport(result, envelopeShaB64)
          const wrongPage = await wrong.request({ path: '/v1/capsules/incoming', method: 'GET' })
          const body = wrongPage.body as { items: Array<Record<string, unknown>> }
          body.items[0]!.recipient_user_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
          await fetchIncoming(rxSession, { request: () => Promise.resolve(wrongPage) })
        } catch (error) {
          wrongRecipientRejected = error instanceof SyncError && error.code === 'SYNC_REJECTED'
        }
        assert(`${label}: wrong recipient metadata rejected by real fetchIncoming`, wrongRecipientRejected)

        let wrongGrantRejected = false
        try {
          await openGrantedPostcard(rxSession, keys, ScanGrant.issue(RECIPIENT_ID, '00000000-0000-4000-8000-000000000000'), item, { blobId: result.content.blobId, ciphertext: contentCiphertext }, sealedPhotos, measurer)
        } catch (error) {
          wrongGrantRejected = error instanceof Error && (error as { code?: string }).code === 'GRANT'
        }
        assert(`${label}: missing/wrong fixture grant cannot open (real GRANT guard)`, wrongGrantRejected)
      } finally {
        // The receiver-returned decrypted JPEG copies and the separately decrypted
        // recognition plaintext are owned by this fixture and wiped here; production
        // open wipes only its own internal plains, not these caller-owned outputs.
        recognitionPlain?.fill(0)
        if (opened) for (const bytes of opened.photos.values()) bytes.fill(0)
        receiverKeys?.drop()
        result.wipe()
      }
    }

    // ---- REAL production sealCapsule -> SAME real receiver round-trip ----
    const runProductionSealRoundTrip = async (label: string, note: string | null): Promise<void> => {
      const snapshot = await armAndFreeze(label, note)
      assert(`${label}: production seal freeze snapshot`, snapshot !== null, String(snapshot))
      if (!snapshot) return
      const plan = snapshot.renderedPlan
      // Present photos in the frozen authored slot order (the sealer requires it).
      const draftPhotos: DraftPhoto[] = snapshot.slots.map((slot) => {
        const photo = photos.find((candidate) => candidate.contentId === slot.contentId)!
        return { id: slot.contentId, bytes: normalizedByContentId.get(slot.contentId)!, width: photo.width, height: photo.height, contentHash: photo.contentHash }
      })
      const senderMemory = senderKeys(session)
      let sealed: SealedCapsule | null = null
      let opened: Awaited<ReturnType<typeof openGrantedPostcard>> | null = null
      let receiverKeys: MemoryKeys | null = null
      try {
        sealed = await sealCapsule({
          session,
          keys: senderMemory,
          freeze: snapshot,
          recipient: RECIPIENT_SNAPSHOT,
          photos: draftPhotos,
          capsuleId: CAPSULE_UUID,
          candidateId: CANDIDATE,
          createdAtEpochSeconds: CREATED_AT,
          epoch: EPOCH,
          frontFingerprint: RECOGNITION_FP,
          placeLabel: null,
        })
        for (const forbidden of ['capsuleKeyset', 'plaintext', 'frontFingerprint', 'expression', 'note', 'wipe']) {
          assert(`${label}: production sealed output has no ${forbidden}`, !(forbidden in (sealed as unknown as Record<string, unknown>)))
        }
        assert(`${label}: production sealer returns ciphertext+public metadata only`, sealed.blobs.length === 5 && sealed.blobs.filter((blob) => blob.kind === 'PHOTO').length === 3)

        const { session: rxSession, keys } = settledSession()
        receiverKeys = keys
        const envelopeShaB64 = await sha256B64(sealed.envelopeCiphertext)
        const transport = sealedTransport(sealed, envelopeShaB64)
        const catalog = await fetchIncoming(rxSession, transport)
        assert(`${label}: production seal fetchIncoming one verified item`, catalog.items.length === 1, String(catalog.items.length))
        const item = catalog.items[0]
        if (!item) return
        const contentBlob = sealed.blobs.find((blob) => blob.kind === 'CONTENT_MANIFEST')!
        const contentCiphertext = await downloadCiphertext(rxSession, transport, item, contentBlob.blobId)
        const sealedPhotos: Array<{ blobId: string; ordinal: number; ciphertext: Uint8Array }> = []
        for (const blob of sealed.blobs) {
          if (blob.kind !== 'PHOTO') continue
          sealedPhotos.push({ blobId: blob.blobId, ordinal: blob.ordinal, ciphertext: await downloadCiphertext(rxSession, transport, item, blob.blobId) })
        }
        opened = await openGrantedPostcard(rxSession, keys, ScanGrant.issue(RECIPIENT_ID, CAPSULE_UUID), item, { blobId: contentBlob.blobId, ciphertext: contentCiphertext }, sealedPhotos, measurer)
        for (const photo of photos) {
          const returned = opened.photos.get(photo.contentId)
          const expected = normalizedByContentId.get(photo.contentId)!
          assert(`${label}: production seal returned decrypted JPEG ${photo.contentId} byte-identical to normalized input`, returned !== undefined && sameBytes(returned, expected))
        }

        const openedScene = opened.scene as Scene
        const decryptedImages = new Map<string, PaintImage>()
        const decryptedBitmaps: ImageBitmap[] = []
        try {
          for (const scenePhoto of openedScene.photos) {
            const jpeg = opened.photos.get(scenePhoto.contentId)
            if (!jpeg) throw new Error(`missing decrypted photo ${scenePhoto.contentId}`)
            const bitmap = await createImageBitmap(new Blob([arrayBuffer(jpeg)], { type: 'image/jpeg' }))
            decryptedBitmaps.push(bitmap)
            decryptedImages.set(scenePhoto.contentId, { source: bitmap, width: bitmap.width, height: bitmap.height })
          }
          traces.fillText.length = 0
          traces.drawImage.length = 0
          assert(`${label}: production seal receiver paints from decrypted JPEGs`, paintScene(receiverContext, openedScene, decryptedImages, null))
          const destinations = traces.drawImage.slice(-plan.slots.length).map((call) => [call.dx, call.dy, call.dw, call.dh])
          assert(`${label}: production seal drawImage destination = frozen slots`, JSON.stringify(destinations) === JSON.stringify(plan.slots.map((slot) => [slot.destination.x, slot.destination.y, slot.destination.width, slot.destination.height])), JSON.stringify(destinations))
          openedScene.photos.forEach((scenePhoto, index) => {
            const frozenSlot = plan.slots[index]!
            assert(
              `${label}: production seal scene photo ${index} geometry = frozen preview`,
              scenePhoto.destination.x === frozenSlot.destination.x &&
                scenePhoto.destination.y === frozenSlot.destination.y &&
                scenePhoto.destination.width === frozenSlot.destination.width &&
                scenePhoto.destination.height === frozenSlot.destination.height &&
                scenePhoto.source.x === frozenSlot.source.x &&
                scenePhoto.source.y === frozenSlot.source.y &&
                scenePhoto.source.width === frozenSlot.source.width &&
                scenePhoto.source.height === frozenSlot.source.height &&
                scenePhoto.shape === frozenSlot.shape,
              JSON.stringify({ got: scenePhoto, want: frozenSlot }),
            )
          })
          assert(`${label}: production seal scene style = frozen style`, openedScene.styleId === snapshot.styleId, `${openedScene.styleId} vs ${snapshot.styleId}`)
          const noteCalls = traces.fillText.filter((call) => call.font.includes('StudySans'))
          if (plan.note === null) {
            assert(`${label}: production seal absent note paints no rows`, noteCalls.length === 0, JSON.stringify(noteCalls))
          } else {
            const paintedRows = noteCalls.map((call) => ({ text: call.text, x: call.x, baseline: call.y }))
            const expectedRows = plan.note.rows.map((row) => ({ text: row.text, x: row.x, baseline: row.baseline }))
            assert(`${label}: production seal note rows/baselines = captured preview`, JSON.stringify(paintedRows) === JSON.stringify(expectedRows), JSON.stringify({ paintedRows, expectedRows }))
          }
          const provenanceTexts = traces.fillText.filter((call) => call.font.includes('StudySerif')).map((call) => call.text)
          assert(`${label}: production seal provenance = captured preview`, plan.provenance.every((mark) => provenanceTexts.includes(mark.text)), JSON.stringify(provenanceTexts))
        } finally {
          for (const bitmap of decryptedBitmaps) bitmap.close()
        }
      } finally {
        if (opened) for (const bytes of opened.photos.values()) bytes.fill(0)
        receiverKeys?.drop()
        if (sealed) for (const blob of sealed.blobs) blob.ciphertext.fill(0)
      }
    }

    await runCryptoRoundTrip('crypto short note', CRYPTO_SHORT)
    await runCryptoRoundTrip('crypto absent note', null)
    await runProductionSealRoundTrip('production seal note', CRYPTO_SEAL_NOTE)
  } catch (error) {
    assert('manifest harness', false, String(error))
  } finally {
    if (built) {
      for (const bitmap of built.bitmaps) {
        try { bitmap.close() } catch { /* best-effort */ }
      }
      for (const bytes of built.bytes.values()) bytes.fill(0)
    }
    rootRef.root?.unmount()
  }
  ;(window as unknown as { __manifest?: unknown }).__manifest = { assertions, failures, fonts }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('manifest main', false, String(error))
  ;(window as unknown as { __manifest?: unknown }).__manifest = { assertions, failures, fonts: null }
  const status = document.querySelector('#manifest-status')
  if (status) status.textContent = 'failed'
})
