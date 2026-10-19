/**
 * T328 unit gate: owner-fenced transient pre-grant recognition index.
 *
 * REAL HPKE envelope open + REAL artifact AEAD via the accepted sealer with
 * a VALID format-3 fingerprint wire (synthetic keypoints, storage-valid
 * all-zero descriptor rows). Synthetic ByteTransport serves the sealed
 * recognition ciphertext only. No content/photo download, no admission, no
 * matcher.
 */
import { describe, expect, it, vi } from 'vitest'
import hpkeVector from '../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { jpegDimensionFixture } from '../src/collage/jpeg'
import type { TextMeasurer } from '../src/collage/prepare'
import { styleById } from '../src/collage/styles'
import { signPublishStatement } from '../src/crypto/publish'
import { openRecipientEnvelope } from '../src/crypto/hpke'
import { decryptArtifact } from '../src/crypto/artifact'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { freezeSelection, type CreatorFreeze } from '../src/create/creatorFreeze'
import type { DraftPhoto } from '../src/create/photos'
import type { RecipientSnapshot } from '../src/create/recipient'
import { sealCapsule } from '../src/create/sealCapsule'
import type { SealedCapsule } from '../src/create/sealCapsule'
import { bytesToHex, concat } from '../src/protocol/bytes'
import { SessionStore } from '../src/session/session'
import { keysetFromJson, type JsonKeyset } from './keysetJson'
import {
  serializeSiftFingerprint,
  type SiftFingerprint,
  type SiftFingerprintKeypoint,
} from '../src/scan/siftFingerprint'
import {
  buildRecognitionIndex,
  RecognitionIndexError,
} from '../src/scan/recognitionIndex'
import type { IncomingBlob, IncomingCapsule } from '../src/sync/incoming'
import type { ApiTransport, ByteTransport } from '../src/auth/http'

const SENDER_ID = '11111111-2222-4333-8444-555555555555'
const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
const SENDER_HANDLE = 'sergio'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const DIRECTORY_VERSION = '0'.repeat(16)
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const NOTE = 'hello'
const CAPSULE_ID = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'

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
  return crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource).then((digest) => new Uint8Array(digest))
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

function validFingerprint(): SiftFingerprint {
  const keypoints: SiftFingerprintKeypoint[] = [
    { xMicro: 100_000, yMicro: 200_000, scaleMicro: 300_000, angleCentiDegrees: 1_234, responseQuantized: 500_000, octave: 0 },
    { xMicro: 400_000, yMicro: 500_000, scaleMicro: 600_000, angleCentiDegrees: 2_345, responseQuantized: 600_000, octave: 1 },
  ]
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: 640,
    canonicalHeightPx: 640,
    coarseHash64: 42n,
    keypoints,
    // NONZERO descriptor rows so every wipe assertion is meaningful.
    quantizedSiftDescriptors: keypoints.map(() => new Uint8Array(128).fill(7)),
  }
}

async function sealWithWire(wire: Uint8Array): Promise<{ sealed: SealedCapsule; senderSession: SessionStore; senderKeys: MemoryKeys }> {
  const photos = [await realPhoto('p0', 1000, 1000), await realPhoto('p1', 1080, 1080), await realPhoto('p2', 1160, 1160)]
  const session = new SessionStore('en')
  session.signIn({
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    handle: SENDER_HANDLE,
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-20T00:00:00Z',
    refreshExpiresAt: '2026-11-20T00:00:00Z',
  })
  session.unlock()
  const caps: KeyCapabilities = {
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    openEnvelope: async () => { throw new Error('unused') },
    decryptArtifact: async () => { throw new Error('unused') },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => {},
  }
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), caps)
  const freeze = buildFreeze(photos, NOTE, session.getSnapshot().generation)
  const ordered = freeze.slots.map((slot) => photos.find((photo) => photo.id === slot.contentId)!)
  const sealed = await sealCapsule({
    session,
    keys,
    freeze,
    recipient: RECIPIENT_SNAPSHOT,
    photos: ordered,
    capsuleId: CAPSULE_ID,
    candidateId: 'cand-1',
    createdAtEpochSeconds: 1_755_000_000n,
    epoch: 1n,
    frontFingerprint: wire,
    placeLabel: null,
  })
  return { sealed, senderSession: session, senderKeys: keys }
}

interface FixtureHooks {
  openEnvelope?: (real: () => Promise<Uint8Array>) => Promise<Uint8Array>
  decryptArtifact?: (real: () => Promise<Uint8Array>) => Promise<Uint8Array>
}

function recipientFixture(sealed: SealedCapsule, hooks: FixtureHooks = {}): {
  session: SessionStore
  keys: MemoryKeys
  item: IncomingCapsule
  catalog: { items: IncomingCapsule[]; hasMore: boolean; nextCursor: string | null }
  downloads: string[]
  served: Uint8Array[]
  transport: ApiTransport & ByteTransport
} {
  const session = new SessionStore('en')
  session.signIn({
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    handle: 'ada',
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-20T00:00:00Z',
    refreshExpiresAt: '2026-11-20T00:00:00Z',
  })
  session.unlock()
  const realOpen = (info: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array> => openRecipientEnvelope(HPKE_PRIVATE, info, ciphertext)
  const caps: KeyCapabilities = {
    accountId: RECIPIENT_ID,
    bundleId: RECIPIENT_BUNDLE,
    openEnvelope: (info, ciphertext) => (hooks.openEnvelope
      ? hooks.openEnvelope(() => realOpen(info, ciphertext))
      : realOpen(info, ciphertext)),
    decryptArtifact: (keyset, aad, ciphertext, kind) => (hooks.decryptArtifact
      ? hooks.decryptArtifact(() => decryptArtifact(keyset, aad, ciphertext, kind))
      : decryptArtifact(keyset, aad, ciphertext, kind)),
    sign: async () => { throw new Error('unused') },
    lock: () => {},
  }
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), caps)
  const blobs: IncomingBlob[] = sealed.blobs.map((blob) => ({
    blobId: blob.blobId,
    kind: blob.kind,
    ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null,
    ciphertextSize: blob.ciphertextSize,
    // The accepted incoming wire stores the declared digest as base64url
    // (downloadCiphertext unb64-decodes it); mirror the real catalog shape.
    ciphertextSha256: b64(blob.ciphertextSha256),
  }))
  const item: IncomingCapsule = {
    capsuleId: sealed.capsuleId,
    senderUserId: sealed.senderUserId,
    recipientUserId: sealed.recipientUserId,
    senderKeyBundleId: sealed.senderKeyBundleId,
    recipientKeyBundleId: sealed.recipientKeyBundleId,
    envelopeCiphertext: sealed.envelopeCiphertext,
    statementSha256: sealed.statementSha256,
    blobs,
  }
  const downloads: string[] = []
  const served: Uint8Array[] = []
  const transport: ApiTransport & ByteTransport = {
    request: async () => { throw new Error('no JSON requests expected') },
    requestBytes: async (input) => {
      downloads.push(input.path)
      const match = /^\/v1\/capsules\/[0-9a-f-]{36}\/blobs\/([0-9a-f-]{36})$/.exec(input.path)
      const blobId = match?.[1]
      const blob = sealed.blobs.find((candidate) => candidate.blobId === blobId)
      if (!blob) return { status: 404, body: new Uint8Array(0) }
      const body = blob.ciphertext.slice()
      served.push(body)
      return { status: 200, body }
    },
  }
  return {
    session,
    keys,
    item,
    catalog: { items: [item], hasMore: false, nextCursor: null },
    downloads,
    served,
    transport,
  }
}

async function expectIndexRefusal(code: 'unavailable' | 'invalid' | 'stale', run: () => Promise<unknown>): Promise<void> {
  try {
    await run()
    expect.unreachable(`expected RecognitionIndexError ${code}`)
  } catch (error) {
    expect(error).toBeInstanceOf(RecognitionIndexError)
    expect((error as RecognitionIndexError).code).toBe(code)
  }
}

describe('buildRecognitionIndex', () => {
  it('real HPKE + AEAD round trip yields one format-3 entry, downloads only the recognition blob, and never leaves keys-unlocked', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const phaseBefore = fixture.session.getSnapshot().phase
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    try {
      const entries = index.entries()
      expect(entries.length).toBe(1)
      expect(entries[0]!.capsuleId).toBe(CAPSULE_ID)
      expect(entries[0]!.fingerprint.keypoints.length).toBe(2)
      expect(entries[0]!.fingerprint.profileId).toBe('postcard-sift-rootsift-v1')
      expect(index.hasMore).toBe(false)
      expect(index.nextCursor).toBeNull()
      expect(fixture.downloads.length).toBe(1)
      expect(fixture.downloads[0]).toContain('/blobs/')
      expect(fixture.session.getSnapshot().phase).toBe(phaseBefore)
      expect(fixture.session.getSnapshot().phase).toBe('keys-unlocked')
    } finally {
      index.dispose()
    }
  })

  it('dispose wipes nonzero descriptor rows, clears entries, and is idempotent; later access refuses stale', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    const rows = index.entries()[0]!.fingerprint.quantizedSiftDescriptors
    expect(rows.length).toBe(2)
    expect(rows.every((row) => row.some((byte) => byte !== 0))).toBe(true)
    index.dispose()
    expect(index.disposed).toBe(true)
    expect(rows.every((row) => row.every((byte) => byte === 0))).toBe(true)
    expect(() => index.entries()).toThrow(RecognitionIndexError)
    index.dispose()
  })

  it('lock after build makes access refuse stale', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    try {
      fixture.session.lock()
      expect(() => index.entries()).toThrow(RecognitionIndexError)
    } finally {
      index.dispose()
    }
  })

  it('a lock during the recognition download fails the whole build as stale with no index', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const original = fixture.transport.requestBytes
    fixture.transport.requestBytes = async (input) => {
      fixture.session.lock()
      return original(input)
    }
    await expectIndexRefusal('stale', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
  })

  it('a tampered envelope fails the whole build as invalid', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const tampered = sealed.envelopeCiphertext.slice()
    tampered[40] = (tampered[40] ?? 0) ^ 0xff
    fixture.item.envelopeCiphertext = tampered
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
  })

  it('a wrong-owner item fails as invalid before any await', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    fixture.catalog.items = [{ ...fixture.item, recipientUserId: SENDER_ID }]
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
    expect(fixture.downloads.length).toBe(0)
  })

  it('a duplicate capsule id fails as invalid', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    fixture.catalog.items = [fixture.item, fixture.item]
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
  })

  it('a missing recognition blob fails as invalid', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    fixture.item.blobs = fixture.item.blobs.filter((blob) => blob.kind !== 'RECOGNITION_MANIFEST')
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
  })

  it('a tampered manifest ciphertext with a consistently re-declared hash fails as invalid at decrypt', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const recognition = sealed.blobs.find((blob) => blob.kind === 'RECOGNITION_MANIFEST')!
    const tampered = recognition.ciphertext.slice()
    tampered[33] = (tampered[33] ?? 0) ^ 0xff // flip a cipher byte; AEAD must refuse
    fixture.transport.requestBytes = async () => ({ status: 200, body: tampered })
    const tamperedSha = b64(await sha256(tampered))
    const item = fixture.item
    item.blobs = item.blobs.map((blob) => (blob.kind === 'RECOGNITION_MANIFEST'
      ? { ...blob, ciphertextSize: tampered.length, ciphertextSha256: tamperedSha }
      : blob))
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
  })

  it('a content/photo download is never requested', async () => {
    const spy = vi.fn()
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const original = fixture.transport.requestBytes
    fixture.transport.requestBytes = async (input) => {
      spy(input.path)
      return original(input)
    }
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    index.dispose()
    const recognition = sealed.blobs.find((blob) => blob.kind === 'RECOGNITION_MANIFEST')!
    expect(spy.mock.calls.length).toBe(1)
    expect(String(spy.mock.calls[0]![0])).toContain(recognition.blobId)
  })

  it('R2: wipes every actually allocated secret temporary BEFORE returning a successful index (only descriptor rows live on)', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const openedBuffers: Uint8Array[] = []
    const plainBuffers: Uint8Array[] = []
    const fixture = recipientFixture(sealed, {
      openEnvelope: async (real) => {
        const buffer = await real()
        openedBuffers.push(buffer)
        return buffer
      },
      decryptArtifact: async (real) => {
        const buffer = await real()
        plainBuffers.push(buffer)
        return buffer
      },
    })
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    try {
      // Rows are live and nonzero; every secret temporary is already zero.
      const rows = index.entries()[0]!.fingerprint.quantizedSiftDescriptors
      expect(rows.every((row) => row.some((byte) => byte !== 0))).toBe(true)
      expect(openedBuffers.length).toBe(1)
      expect(plainBuffers.length).toBe(1)
      expect(fixture.served.length).toBe(1)
      expect(openedBuffers[0]!.every((byte) => byte === 0)).toBe(true)
      expect(plainBuffers[0]!.every((byte) => byte === 0)).toBe(true)
      expect(fixture.served[0]!.every((byte) => byte === 0)).toBe(true)
    } finally {
      index.dispose()
    }
  })

  it('R2: session lock immediately wipes live descriptor rows without waiting for entries()/matcher settlement', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    const rows = index.entries()[0]!.fingerprint.quantizedSiftDescriptors
    expect(rows.every((row) => row.some((byte) => byte !== 0))).toBe(true)
    fixture.session.lock()
    expect(index.disposed).toBe(true)
    expect(rows.every((row) => row.every((byte) => byte === 0))).toBe(true)
    expect(() => index.entries()).toThrow(RecognitionIndexError)
    index.dispose()
  })

  it('R2: signal abort immediately wipes live descriptor rows', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed)
    const controller = new AbortController()
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
      signal: controller.signal,
    })
    const rows = index.entries()[0]!.fingerprint.quantizedSiftDescriptors
    expect(rows.every((row) => row.some((byte) => byte !== 0))).toBe(true)
    controller.abort()
    expect(index.disposed).toBe(true)
    expect(rows.every((row) => row.every((byte) => byte === 0))).toBe(true)
    index.dispose()
  })

  it('R2: a lock during the envelope await wipes the late plaintext immediately even though the build still rejects stale', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const openedBuffers: Uint8Array[] = []
    const fixture = recipientFixture(sealed, {
      openEnvelope: async (real) => {
        const buffer = await real()
        // The invalidation lands AFTER the crypto returned a fresh plaintext
        // (the late-arrival case): the scope must wipe it immediately.
        fixture.session.lock()
        openedBuffers.push(buffer)
        return buffer
      },
    })
    await expectIndexRefusal('stale', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
    expect(openedBuffers.length).toBe(1)
    expect(openedBuffers[0]!.every((byte) => byte === 0)).toBe(true)
  })

  it('R2: preserves the original page flags across the envelope await (immutable snapshot)', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const fixture = recipientFixture(sealed, {
      openEnvelope: async (real) => {
        // Mutate the CALLER catalog while the first await is pending.
        fixture.catalog.hasMore = true
        fixture.catalog.nextCursor = 'mutated-cursor'
        return real()
      },
    })
    const index = await buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    })
    try {
      expect(index.hasMore).toBe(false)
      expect(index.nextCursor).toBeNull()
    } finally {
      index.dispose()
    }
  })

  it('R2: an unknown envelope field after a valid prefix refuses invalid and wipes the opened plaintext', async () => {
    const wire = serializeSiftFingerprint(validFingerprint())
    const { sealed } = await sealWithWire(wire)
    const openedBuffers: Uint8Array[] = []
    const fixture = recipientFixture(sealed, {
      openEnvelope: async (real) => {
        const plain = await real()
        // Append an unexpected root field 10 (varint 1) after the valid envelope.
        const extended = concat([plain, Uint8Array.of((10 << 3) | 0, 0x01)])
        openedBuffers.push(extended)
        return extended
      },
    })
    await expectIndexRefusal('invalid', () => buildRecognitionIndex({
      session: fixture.session,
      keys: fixture.keys,
      transport: fixture.transport,
      catalog: fixture.catalog,
    }))
    expect(openedBuffers.length).toBe(1)
    expect(openedBuffers[0]!.every((byte) => byte === 0)).toBe(true)
  })
})
