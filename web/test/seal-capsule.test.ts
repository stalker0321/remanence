import { afterEach, describe, expect, it, vi } from 'vitest'
import hpkeVector from '../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { jpegDimensionFixture } from '../src/collage/jpeg'
import { openGrantedPostcard } from '../src/collage/open'
import type { TextMeasurer } from '../src/collage/prepare'
import { styleById } from '../src/collage/styles'
import { decryptArtifact } from '../src/crypto/artifact'
import { openRecipientEnvelope } from '../src/crypto/hpke'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { signPublishStatement, verifyPublishStatement } from '../src/crypto/publish'
import { freezeSelection, type CreatorFreeze } from '../src/create/creatorFreeze'
import type { DraftPhoto } from '../src/create/photos'
import type { RecipientSnapshot } from '../src/create/recipient'
import { sealCapsule, SealCapsuleError } from '../src/create/sealCapsule'
import { arrayBuffer, bytesToHex } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { ScanGrant } from '../src/scan/grant'
import { SessionStore } from '../src/session/session'
import { downloadCiphertext, fetchIncoming } from '../src/sync/incoming'
import type { ApiTransport, ByteTransport } from '../src/auth/http'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

const SENDER_ID = '11111111-2222-4333-8444-555555555555'
const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const CAPSULE_UUID = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
const SENDER_HANDLE = 'sergio'
const DIRECTORY_VERSION = '0'.repeat(16)
const CANDIDATE = 'cand-1'
const CREATED_AT = 1_755_000_000n
const EPOCH = 17n
const FP = Uint8Array.from({ length: 32 }, (_value, index) => index + 1)
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'

const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
const HPKE_PRIVATE = keysetFromJson(hpkeVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

const RECIPIENT_SNAPSHOT: RecipientSnapshot = {
  userId: RECIPIENT_ID,
  handle: 'ada',
  keyBundleId: RECIPIENT_BUNDLE,
  suite: SUITE,
  protocolVersion: 1,
  encryptionPublicKeyset: b64(HPKE_PUBLIC),
  signingPublicKeyset: b64(SIGNING_PUBLIC),
  directoryVersion: DIRECTORY_VERSION,
  self: false,
}

const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return crypto.subtle.digest('SHA-256', arrayBuffer(bytes)).then((digest) => new Uint8Array(digest))
}

async function realPhoto(id: string, width: number, height: number): Promise<DraftPhoto> {
  const bytes = jpegDimensionFixture(width, height)
  return { id, bytes, width, height, contentHash: bytesToHex(await sha256(bytes)) }
}

function buildFreeze(photos: readonly DraftPhoto[], note: string | null, sessionGeneration: number): CreatorFreeze {
  const source = photos.map((photo) => ({ contentId: photo.id, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
  const byId = new Map(source.map((spec) => [spec.contentId, spec]))
  const style = styleById('press')!
  const view = previewCandidates({ id: 'creator', sender: SENDER_HANDLE, photos: source, note }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view,
    styleId: 'press',
    senderHandle: SENDER_HANDLE,
    noteText: note,
    photos: new Map(source.map((spec) => [spec.contentId, { width: spec.width, height: spec.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) =>
          measurer.measure({
            text: line,
            family: scene.note!.family,
            size: scene.note!.size,
            weight: scene.note!.weight,
            italic: scene.note!.italic,
            line: scene.note!.line,
            maxWidth: Number.POSITIVE_INFINITY,
            maxHeight: Number.POSITIVE_INFINITY,
            softWrap: false,
          }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: 'press', styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch,
    styleId: 'press',
    styleVersion: style.version,
    senderHandle: SENDER_HANDLE,
    slots: view.slots.map((slot, index) => {
      const spec = byId.get(slot.photoId)!
      return { contentId: slot.photoId, ordinal: index, contentHash: spec.contentHash, widthPx: spec.width, heightPx: spec.height, crop: { ...slot.crop } }
    }),
    note,
    noteRegion: scene.note ? { ...scene.note.region } : null,
    noteFits: true,
    musicSelected: false,
    ownerId: SENDER_ID,
    sessionGeneration,
    recipient: { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: DIRECTORY_VERSION },
    renderedKey: 'rk-1',
    renderedPlan,
    createdAtMs: 1,
  })
  if (!result.ok) throw new Error('freeze failed')
  return result.frozen
}

interface SenderFixture {
  session: SessionStore
  keys: MemoryKeys
  caps: KeyCapabilities
  freeze: CreatorFreeze
  photos: DraftPhoto[]
}

async function senderFixture(note: string | null = 'hello'): Promise<SenderFixture> {
  const photos = [await realPhoto('p0', 1000, 1000), await realPhoto('p1', 1080, 1080), await realPhoto('p2', 1160, 1160)]
  const session = new SessionStore('en')
  session.signIn({
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    handle: SENDER_HANDLE,
    accessToken: 'sender-access',
    refreshToken: 'sender-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  const caps: KeyCapabilities = {
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    openEnvelope: async () => {
      throw new Error('unused')
    },
    decryptArtifact: async () => {
      throw new Error('unused')
    },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => {},
  }
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), caps)
  const freeze = buildFreeze(photos, note, session.getSnapshot().generation)
  return { session, keys, caps, freeze, photos }
}

function seal(sender: SenderFixture, overrides: Partial<Parameters<typeof sealCapsule>[0]> = {}) {
  return sealCapsule({
    session: sender.session,
    keys: sender.keys,
    freeze: sender.freeze,
    recipient: RECIPIENT_SNAPSHOT,
    photos: sender.photos,
    capsuleId: CAPSULE_UUID,
    candidateId: CANDIDATE,
    createdAtEpochSeconds: CREATED_AT,
    epoch: EPOCH,
    frontFingerprint: FP,
    placeLabel: null,
    ...overrides,
  })
}

function sealedPage(sealed: Awaited<ReturnType<typeof sealCapsule>>, envelopeShaB64: string): Record<string, unknown> {
  return {
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
        recipient_envelope: { recipient_key_bundle_id: sealed.recipientKeyBundleId, ciphertext: b64(sealed.envelopeCiphertext), ciphertext_size: sealed.envelopeCiphertext.length, ciphertext_sha256: envelopeShaB64 },
        blobs: sealed.blobs.map((blob) => ({ blob_id: blob.blobId, kind: blob.kind, ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null, ciphertext_size: blob.ciphertextSize, ciphertext_sha256: b64(blob.ciphertextSha256) })),
      },
    ],
    has_more: false,
    next_cursor: null,
  }
}

function sealedTransport(page: Record<string, unknown>, bytes: Map<string, Uint8Array>): ApiTransport & ByteTransport {
  const directory = { key_bundle_id: SENDER_BUNDLE, user_id: SENDER_ID, suite: 'tink', protocol_version: 1, encryption_public_keyset: b64(SIGNING_PUBLIC), signing_public_keyset: b64(SIGNING_PUBLIC), status: 'ACTIVE', created_at: '2023-11-14T22:13:20Z' }
  return {
    async request(input) {
      return input.path === '/v1/capsules/incoming' ? { status: 200, body: page } : { status: 200, body: directory }
    },
    async requestBytes(input) {
      const blobId = input.path.slice(input.path.lastIndexOf('/') + 1)
      const body = bytes.get(blobId)
      return body ? { status: 200, body } : { status: 404, body: new Uint8Array() }
    },
  }
}

function receivingSession(): { session: SessionStore; keys: MemoryKeys } {
  const session = new SessionStore('en')
  session.signIn({
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    handle: 'ada',
    accessToken: 'receiver-access',
    refreshToken: 'receiver-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  session.matchPostcard()
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), {
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    openEnvelope: (info, ciphertext) => openRecipientEnvelope(HPKE_PRIVATE, info, ciphertext),
    decryptArtifact: (keyset, aad, ciphertext, kind) => decryptArtifact(keyset, aad, ciphertext, kind),
    sign: async () => {
      throw new Error('unused')
    },
    lock: () => {},
  })
  return { session, keys }
}

async function openSealed(sealed: Awaited<ReturnType<typeof sealCapsule>>) {
  const received = receivingSession()
  const envelopeShaB64 = b64(await sha256(sealed.envelopeCiphertext))
  const bytes = new Map<string, Uint8Array>(sealed.blobs.map((blob) => [blob.blobId, blob.ciphertext] as const))
  const transport = sealedTransport(sealedPage(sealed, envelopeShaB64), bytes)
  const catalog = await fetchIncoming(received.session, transport)
  const item = catalog.items[0]
  if (!item) throw new Error('no item')
  const contentBlob = sealed.blobs.find((blob) => blob.kind === 'CONTENT_MANIFEST')!
  const content = { blobId: contentBlob.blobId, ciphertext: await downloadCiphertext(received.session, transport, item, contentBlob.blobId) }
  const sealedPhotos = []
  for (const blob of sealed.blobs) {
    if (blob.kind !== 'PHOTO') continue
    sealedPhotos.push({ blobId: blob.blobId, ordinal: blob.ordinal, ciphertext: await downloadCiphertext(received.session, transport, item, blob.blobId) })
  }
  const opened = await openGrantedPostcard(received.session, received.keys, ScanGrant.issue(RECIPIENT_ID, CAPSULE_UUID), item, content, sealedPhotos, measurer)
  return { opened, received }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('production sealCapsule', () => {
  it('exports only the production sealer + error (no observer/keyset handle)', async () => {
    const mod = await import('../src/create/sealCapsule')
    expect(Object.keys(mod).sort()).toEqual(['SealCapsuleError', 'sealCapsule'])
  })

  it('returns ciphertext + public metadata only with no key/plain/fingerprint fields', async () => {
    const sender = await senderFixture()
    const sealed = await seal(sender)
    expect(Object.keys(sealed).sort()).toEqual([
      'blobs', 'capsuleId', 'envelopeCiphertext', 'recipientKeyBundleId', 'recipientUserId',
      'senderKeyBundleId', 'senderUserId', 'signature', 'statement', 'statementSha256',
    ].sort())
    for (const forbidden of ['capsuleKeyset', 'keyset', 'plaintext', 'frontFingerprint', 'expression', 'note', 'wipe']) {
      expect(forbidden in sealed).toBe(false)
    }
    expect(sealed.capsuleId).toBe(CAPSULE_UUID)
    expect(sealed.senderUserId).toBe(SENDER_ID)
    expect(sealed.senderKeyBundleId).toBe(SENDER_BUNDLE)
    expect(sealed.recipientUserId).toBe(RECIPIENT_ID)
    expect(sealed.recipientKeyBundleId).toBe(RECIPIENT_BUNDLE)
    expect(sealed.blobs.map((blob) => `${blob.kind}:${blob.ordinal}`).sort()).toEqual([
      'CONTENT_MANIFEST:-1', 'PHOTO:0', 'PHOTO:1', 'PHOTO:2', 'RECOGNITION_MANIFEST:-1',
    ])
    expect(bytesToHex(sealed.statementSha256)).toBe(bytesToHex(await sha256(sealed.statement)))
    await expect(verifyPublishStatement(SIGNING_PUBLIC, sealed.statement, sealed.signature)).resolves.toBeUndefined()
  })

  it('seals output that opens through the real receiver with byte-identical decrypted photos', async () => {
    const sender = await senderFixture('hello world')
    const sealed = await seal(sender)
    const { opened } = await openSealed(sealed)
    for (const photo of sender.photos) {
      const returned = opened.photos.get(photo.id)
      expect(returned).toBeDefined()
      expect(bytesToHex(returned!)).toBe(bytesToHex(photo.bytes))
    }
  })

  it('refuses wrong phase/owner/handle/bundle/generation/recipient before any crypto', async () => {
    const sender = await senderFixture()
    // signed-out
    const out = await senderFixture()
    out.session.signOut()
    await expect(seal(out)).rejects.toMatchObject({ code: 'phase' })
    // wrong owner (different account)
    const other = await senderFixture()
    other.session.signIn({ accountId: '99999999-9999-4999-8999-999999999999', bundleId: SENDER_BUNDLE, handle: SENDER_HANDLE, accessToken: 'a', refreshToken: 'r', accessExpiresAt: '2026-10-17T00:00:00Z', refreshExpiresAt: '2026-11-17T00:00:00Z' })
    other.session.unlock()
    await expect(seal(other)).rejects.toMatchObject({ code: 'owner' })
    // wrong handle
    const badHandle = await senderFixture()
    badHandle.freeze = { ...badHandle.freeze, senderHandle: 'someone' }
    await expect(seal(badHandle)).rejects.toMatchObject({ code: 'owner' })
    // wrong generation
    const badGen = await senderFixture()
    badGen.freeze = { ...badGen.freeze, sessionGeneration: badGen.freeze.sessionGeneration + 1 }
    await expect(seal(badGen)).rejects.toMatchObject({ code: 'stale' })
    // recipient tuple mismatch
    await expect(seal(sender, { recipient: { ...RECIPIENT_SNAPSHOT, keyBundleId: '99999999-9999-4999-8999-999999999999' } })).rejects.toMatchObject({ code: 'recipient' })
    // unsupported suite
    await expect(seal(sender, { recipient: { ...RECIPIENT_SNAPSHOT, suite: 'BOGUS' as never } })).rejects.toMatchObject({ code: 'recipient' })
    // wrong caps bundle
    const badBundle = await senderFixture()
    const wrongCaps: KeyCapabilities = { ...badBundle.caps, bundleId: '99999999-9999-4999-8999-999999999999' }
    badBundle.keys.hold(badBundle.session.getSnapshot(), wrongCaps)
    await expect(seal(badBundle)).rejects.toMatchObject({ code: 'bundle' })
  })

  it('refuses non-canonical contract scalars before any crypto', async () => {
    const sender = await senderFixture()
    await expect(seal(sender, { capsuleId: 'not-a-uuid' })).rejects.toMatchObject({ code: 'contract' })
    await expect(seal(sender, { createdAtEpochSeconds: -1n })).rejects.toMatchObject({ code: 'contract' })
    await expect(seal(sender, { epoch: BigInt('9223372036854775808') })).rejects.toMatchObject({ code: 'contract' })
  })

  it('refuses photo set/JPEG/hash mismatches before signing', async () => {
    const sender = await senderFixture()
    const signSpy = vi.fn(sender.caps.sign)
    sender.keys.hold(sender.session.getSnapshot(), { ...sender.caps, sign: signSpy })
    // missing/extra
    await expect(seal(sender, { photos: sender.photos.slice(0, 2) })).rejects.toMatchObject({ code: 'photo' })
    // wrong hash
    const wrongHash = sender.photos.map((photo, index) => (index === 0 ? { ...photo, contentHash: '0'.repeat(64) } : photo))
    await expect(seal(sender, { photos: wrongHash })).rejects.toMatchObject({ code: 'photo' })
    // non-JPEG bytes
    const nonJpeg = sender.photos.map((photo, index) => (index === 0 ? { ...photo, bytes: new Uint8Array([1, 2, 3, 4]) } : photo))
    await expect(seal(sender, { photos: nonJpeg })).rejects.toMatchObject({ code: 'photo' })
    // dims/SOF mismatch
    const badDims = sender.photos.map((photo, index) => (index === 0 ? { ...photo, width: photo.width + 2 } : photo))
    await expect(seal(sender, { photos: badDims })).rejects.toMatchObject({ code: 'photo' })
    expect(signSpy).not.toHaveBeenCalled()
  })

  it('does not sign or seal when the owned JPEG hash does not match the frozen hash', async () => {
    const sender = await senderFixture()
    const signSpy = vi.fn(sender.caps.sign)
    sender.keys.hold(sender.session.getSnapshot(), { ...sender.caps, sign: signSpy })
    const tampered = sender.photos.map((photo, index) => (index === 0 ? { ...photo, bytes: jpegDimensionFixture(photo.width, photo.height - 2) } : photo))
    await expect(seal(sender, { photos: tampered })).rejects.toBeInstanceOf(SealCapsuleError)
    expect(signSpy).not.toHaveBeenCalled()
  })

  it('captures caller inputs before the first await: deferred mutation cannot change the result', async () => {
    const sender = await senderFixture()
    const originalBytes = sender.photos.map((photo) => photo.bytes.slice())
    const originalFp = FP.slice()
    const realDigest = crypto.subtle.digest.bind(crypto.subtle)
    let first = true
    vi.spyOn(crypto.subtle, 'digest').mockImplementation(((algorithm: AlgorithmIdentifier, data: BufferSource) => {
      if (first) {
        first = false
        return (async () => {
          await new Promise((resolve) => setTimeout(resolve, 25))
          return realDigest(algorithm, data)
        })()
      }
      return realDigest(algorithm, data)
    }) as typeof crypto.subtle.digest)
    const pending = seal(sender)
    sender.photos[0]!.bytes.fill(0xee)
    FP.fill(0xcc)
    const sealed = await pending
    const { opened } = await openSealed(sealed)
    for (let index = 0; index < sender.photos.length; index++) {
      expect(bytesToHex(opened.photos.get(sender.photos[index]!.id)!)).toBe(bytesToHex(originalBytes[index]!))
    }
    // The captured fingerprint still made it into the recognition artifact; the
    // mutated caller array is not the one used.
    expect(bytesToHex(FP)).not.toBe(bytesToHex(originalFp))
  })

  it('fails closed with a typed stale refusal when the session/caps change during async', async () => {
    const sender = await senderFixture()
    const realDigest = crypto.subtle.digest.bind(crypto.subtle)
    let first = true
    vi.spyOn(crypto.subtle, 'digest').mockImplementation(((algorithm: AlgorithmIdentifier, data: BufferSource) => {
      if (first) {
        first = false
        return (async () => {
          await new Promise((resolve) => setTimeout(resolve, 20))
          sender.keys.drop()
          return realDigest(algorithm, data)
        })()
      }
      return realDigest(algorithm, data)
    }) as typeof crypto.subtle.digest)
    await expect(seal(sender)).rejects.toMatchObject({ code: 'stale' })
  })

  it('wipes owned photo/fingerprint copies on success and on a typed failure', async () => {
    const sender = await senderFixture()
    const originals = [...sender.photos.map((photo) => photo.bytes), FP]
    const zeroed = new Set<Uint8Array>()
    let matched: Uint8Array[] = []
    const realSlice = Uint8Array.prototype.slice
    const realFill = Uint8Array.prototype.fill
    const matchesOriginal = (candidate: Uint8Array): boolean =>
      originals.some((original) => original.length === candidate.length && original.every((byte, index) => byte === candidate[index]))
    vi.spyOn(Uint8Array.prototype, 'slice').mockImplementation((function (this: Uint8Array, start?: number, end?: number) {
      const result = realSlice.call(this, start, end)
      if (matchesOriginal(result)) matched.push(result)
      return result
    }) as unknown as typeof Uint8Array.prototype.slice)
    vi.spyOn(Uint8Array.prototype, 'fill').mockImplementation((function (this: Uint8Array, value: number, start?: number, end?: number) {
      if (value === 0 && start === undefined) zeroed.add(this)
      return realFill.call(this, value, start, end)
    }) as unknown as typeof Uint8Array.prototype.fill)
    await seal(sender)
    expect(matched.length).toBeGreaterThan(0)
    for (const copy of matched) expect(zeroed.has(copy)).toBe(true)

    matched = []
    zeroed.clear()
    const failing = await senderFixture()
    const sign = vi.fn(async () => {
      throw new ProtocolError('BAD_SIGNATURE')
    })
    failing.keys.hold(failing.session.getSnapshot(), { ...failing.caps, sign })
    await expect(seal(failing)).rejects.toMatchObject({ code: 'sign' })
    expect(matched.length).toBeGreaterThan(0)
    for (const copy of matched) expect(zeroed.has(copy)).toBe(true)
  })
})
