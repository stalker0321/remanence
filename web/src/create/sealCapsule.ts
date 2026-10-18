import { unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import type { MemoryKeys } from '../auth/memoryKeys'
import { jpegSize } from '../collage/jpeg'
import { buildFrozenExpression } from '../collage/frozenExpression'
import { encodeContentManifest } from '../collage/manifest'
import { encodeRecognitionManifest } from '../collage/recognitionManifest'
import { encryptArtifact } from '../crypto/artifactEncrypt'
import { generateCapsuleKeyset } from '../crypto/capsuleKeyset'
import { sealRecipientEnvelope } from '../crypto/hpkeSeal'
import { buildRecipientEnvelopePlaintext } from '../crypto/recipientEnvelope'
import { bytesToHex } from '../protocol/bytes'
import { artifactAad, recipientEnvelopeInfo } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { limits } from '../protocol/limits'
import { buildPublishStatement, type PublishStatementArtifact } from '../protocol/publishStatement'
import { uuidToBytes } from '../protocol/uuid'
import type { SessionStore } from '../session/session'
import type { CreatorFreeze } from './creatorFreeze'
import { NORMALIZED_PHOTO_MAX_LONG_EDGE_PX, NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES } from './normalize'
import type { DraftPhoto } from './photos'
import type { RecipientSnapshot } from './recipient'

/**
 * T296 — ONE production ciphertext-only local capsule sealer.
 *
 * Composes the already accepted protocol/crypto primitives (GENEX04 expression
 * + RecognitionManifest/ContentManifest encoders, Tink AEAD encrypt, publish
 * statement, the account's existing Ed25519 `KeyCapabilities.sign`, raw
 * statement SHA-256, recipient envelope plaintext + real HPKE seal) and returns
 * CIPHERTEXT + PUBLIC METADATA ONLY. It never accepts or exposes a raw private
 * signing key, a capsule AEAD keyset, a plaintext, a photo byte, the
 * recognition fingerprint, the note/expression, or any wipe handle.
 *
 * This is NOT publish/outbox/network/UI Send and NOT real recognition. The
 * caller supplies an explicit opaque front fingerprint from a genuine upstream
 * provider; web has no FRONT provider wired yet, so this module cannot be
 * connected to a real send on its own. There are no clocks, default or
 * generated identifiers, or new directory/rotation/trust policy.
 *
 * The owner/generation/capability guard here fences the SESSION/KEY identity
 * around the awaited work and the return. It is deliberately NOT `App.freezeValid`
 * (UI draft/edit invalidation) and NOT a publish authorization: this module
 * cannot observe live draft edits and does not claim a stale publish is
 * impossible without an actual publisher.
 */

export type SealCapsuleErrorCode =
  | 'phase'
  | 'owner'
  | 'bundle'
  | 'stale'
  | 'recipient'
  | 'photo'
  | 'expression'
  | 'recognition'
  | 'content'
  | 'statement'
  | 'sign'
  | 'envelope'
  | 'contract'

/** Typed, secret-free operational refusal. `message` is always the code. */
export class SealCapsuleError extends Error {
  constructor(readonly code: SealCapsuleErrorCode) {
    super(code)
    this.name = 'SealCapsuleError'
  }
}

export interface SealCapsuleInput {
  session: SessionStore
  keys: MemoryKeys
  freeze: CreatorFreeze
  recipient: RecipientSnapshot
  photos: readonly DraftPhoto[]
  capsuleId: string
  candidateId: string
  createdAtEpochSeconds: bigint
  epoch: bigint
  frontFingerprint: Uint8Array
  placeLabel: string | null
}

export interface SealedCapsuleBlob {
  blobId: string
  kind: 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO'
  /** `-1` for the two non-photo artifacts, matching the statement builder. */
  ordinal: number
  ciphertext: Uint8Array
  ciphertextSize: number
  ciphertextSha256: Uint8Array
}

export interface SealedCapsule {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  statement: Uint8Array
  signature: Uint8Array
  statementSha256: Uint8Array
  envelopeCiphertext: Uint8Array
  blobs: SealedCapsuleBlob[]
}

const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
/** Android `CapsulePublisher`/`CapsulePhotoIdentity` byte-0 blob tags. */
const RECOGNITION_BLOB_BYTE = 0x01
const CONTENT_BLOB_BYTE = 0x02
const PHOTO_BLOB_TAG_BASE = 0x10
const INT64_MAX = 2n ** 63n - 1n
const CONTENT_HASH = /^[0-9a-f]{64}$/

function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  // Hash the owned view directly. `arrayBuffer()` would allocate a SECOND full
  // plaintext copy (notably for each photo JPEG) that this module does not own
  // and would never wipe; WebCrypto accepts the view as a BufferSource.
  return crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource).then((digest) => new Uint8Array(digest))
}

function formatUuid(bytes: Uint8Array): string {
  const hex = bytesToHex(bytes)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Capsule UUID with byte 0 replaced by the tag byte (Android identity). */
function tagBlobId(capsuleBytes: Uint8Array, tag: number): string {
  const bytes = capsuleBytes.slice()
  bytes[0] = tag
  return formatUuid(bytes)
}

/** Map a known typed crypto/encoding failure to a bounded sealer code; rethrow anything else. */
function mapFailure(error: unknown, code: SealCapsuleErrorCode): SealCapsuleError {
  if (error instanceof SealCapsuleError) return error
  if (error instanceof ProtocolError) return new SealCapsuleError(error.code === 'LOCKED' ? 'stale' : code)
  throw error
}

export async function sealCapsule(input: SealCapsuleInput): Promise<SealedCapsule> {
  const { session, keys, freeze, recipient, photos, capsuleId, candidateId, createdAtEpochSeconds, epoch, frontFingerprint, placeLabel } = input

  // ---- synchronous preflight + capture (no await yet) ----
  const view = session.getSnapshot()
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) throw new SealCapsuleError('phase')
  const caps = keys.capabilities(view)
  if (view.accountId !== freeze.ownerId) throw new SealCapsuleError('owner')
  if (view.handle === null || view.handle !== freeze.senderHandle) throw new SealCapsuleError('owner')
  if (view.bundleId === null || caps === null || caps.accountId !== view.accountId || caps.bundleId !== view.bundleId) {
    throw new SealCapsuleError('bundle')
  }
  if (view.generation !== freeze.sessionGeneration) throw new SealCapsuleError('stale')
  if (
    recipient.userId !== freeze.recipient.userId
    || recipient.keyBundleId !== freeze.recipient.keyBundleId
    || recipient.directoryVersion !== freeze.recipient.directoryVersion
  ) {
    throw new SealCapsuleError('recipient')
  }
  if (recipient.suite !== SUITE || recipient.protocolVersion !== 1) throw new SealCapsuleError('recipient')
  if (typeof createdAtEpochSeconds !== 'bigint' || createdAtEpochSeconds < 0n || createdAtEpochSeconds > INT64_MAX) throw new SealCapsuleError('contract')
  if (typeof epoch !== 'bigint' || epoch < 0n || epoch > INT64_MAX) throw new SealCapsuleError('contract')

  // Own every mutable input before the first await. Registered for the finally
  // wipe as soon as acquired, so a later failure cannot leak an earlier copy.
  const owned: Uint8Array[] = []
  let capsuleKeyset: Uint8Array | null = null
  // Allocate AND register an owned copy BEFORE the fallible write, so a partial
  // copy (a test-only `set` spy that writes then throws) is still wiped.
  const ownCopy = (source: Uint8Array): Uint8Array => {
    const copy = new Uint8Array(source.length)
    owned.push(copy)
    copy.set(source)
    return copy
  }

  try {
    let ownedFreeze: CreatorFreeze
    let ownedRecipient: RecipientSnapshot
    try {
      ownedFreeze = structuredClone(freeze) as CreatorFreeze
      ownedRecipient = structuredClone(recipient) as RecipientSnapshot
    } catch {
      throw new SealCapsuleError('contract')
    }

    let capsuleBytes: Uint8Array
    let senderBytes: Uint8Array
    let recipientUserBytes: Uint8Array
    let senderBundleBytes: Uint8Array
    let recipientBundleBytes: Uint8Array
    try {
      capsuleBytes = uuidToBytes(capsuleId)
      senderBytes = uuidToBytes(ownedFreeze.ownerId)
      recipientUserBytes = uuidToBytes(ownedFreeze.recipient.userId)
      senderBundleBytes = uuidToBytes(view.bundleId)
      recipientBundleBytes = uuidToBytes(ownedFreeze.recipient.keyBundleId)
    } catch {
      throw new SealCapsuleError('contract')
    }

    let recipientPublic: Uint8Array
    try {
      recipientPublic = unb64(ownedRecipient.encryptionPublicKeyset) as Uint8Array
    } catch {
      throw new SealCapsuleError('recipient')
    }
    owned.push(recipientPublic)
    if (!(recipientPublic instanceof Uint8Array) || recipientPublic.length === 0) throw new SealCapsuleError('recipient')

    if (!(frontFingerprint instanceof Uint8Array) || frontFingerprint.length === 0) throw new SealCapsuleError('recognition')
    const ownedFingerprint = ownCopy(frontFingerprint)

    const slots = ownedFreeze.slots
    if (!Array.isArray(slots) || slots.length < 3 || slots.length > 5) throw new SealCapsuleError('photo')
    if (!Array.isArray(photos) || photos.length !== slots.length) throw new SealCapsuleError('photo')
    const ownedPhotoBytes = new Map<string, Uint8Array>()
    const seenIds = new Set<string>()
    for (let index = 0; index < slots.length; index++) {
      const slot = slots[index]!
      if (slot.ordinal !== index) throw new SealCapsuleError('photo')
      const photo = photos[index]
      // Authored slot order is required: no missing/extra/duplicate/reordered
      // substitution. The caller presents photos in the frozen slot order.
      if (!photo || typeof photo.id !== 'string' || photo.id !== slot.contentId || seenIds.has(photo.id)) throw new SealCapsuleError('photo')
      seenIds.add(photo.id)
      if (photo.width !== slot.widthPx || photo.height !== slot.heightPx) throw new SealCapsuleError('photo')
      if (!(photo.bytes instanceof Uint8Array) || photo.bytes.length === 0 || photo.bytes.length > NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES) {
        throw new SealCapsuleError('photo')
      }
      if (
        !Number.isInteger(slot.widthPx) || !Number.isInteger(slot.heightPx)
        || slot.widthPx < 1 || slot.heightPx < 1
        || slot.widthPx > NORMALIZED_PHOTO_MAX_LONG_EDGE_PX || slot.heightPx > NORMALIZED_PHOTO_MAX_LONG_EDGE_PX
      ) {
        throw new SealCapsuleError('photo')
      }
      const size = jpegSize(photo.bytes)
      if (!size || size.width !== slot.widthPx || size.height !== slot.heightPx) throw new SealCapsuleError('photo')
      if (typeof slot.contentHash !== 'string' || !CONTENT_HASH.test(slot.contentHash)) throw new SealCapsuleError('photo')
      if (typeof photo.contentHash !== 'string' || photo.contentHash !== slot.contentHash) throw new SealCapsuleError('photo')
      const copy = ownCopy(photo.bytes)
      ownedPhotoBytes.set(slot.contentId, copy)
    }

    // Session/key identity guard: same visit, same account/bundle, SAME caps object.
    const fresh = (): void => {
      const next = session.getSnapshot()
      const current = keys.capabilities(next)
      if (
        next.generation !== view.generation
        || next.phase === 'signed-out'
        || next.phase === 'authenticated'
        || next.accountId !== view.accountId
        || next.bundleId !== view.bundleId
        || current !== caps
      ) {
        throw new SealCapsuleError('stale')
      }
    }
    const withFresh = async <T>(work: () => Promise<T>): Promise<T> => {
      fresh()
      const value = await work()
      fresh()
      return value
    }

    // Owned-JPEG SHA-256 must equal the frozen lowercase contentHash before any
    // encryption; the SOF check above already bound the dimensions (header only,
    // never a decode/transcode claim).
    for (let index = 0; index < slots.length; index++) {
      const slot = slots[index]!
      const copy = ownedPhotoBytes.get(slot.contentId)!
      fresh()
      const digest = await sha256(copy)
      owned.push(digest) // own before the post-await fence; wiped in the finally
      fresh()
      if (bytesToHex(digest) !== slot.contentHash) throw new SealCapsuleError('photo')
    }

    fresh()
    const built = await buildFrozenExpression(ownedFreeze, epoch)
    if (built.ok) owned.push(built.bytes) // own before the post-await fence
    fresh()
    if (!built.ok) throw new SealCapsuleError('expression')

    // Recognition manifest over the explicit opaque upstream fingerprint.
    const recognitionBlobId = tagBlobId(capsuleBytes, RECOGNITION_BLOB_BYTE)
    const contentBlobId = tagBlobId(capsuleBytes, CONTENT_BLOB_BYTE)
    const recognition = encodeRecognitionManifest({
      capsuleId: capsuleBytes,
      senderHandleSnapshot: ownedFreeze.senderHandle,
      createdAtEpochSeconds,
      placeLabel,
      frontFingerprint: ownedFingerprint,
    })
    if (!recognition.ok) throw new SealCapsuleError('recognition')
    owned.push(recognition.bytes)

    const blobIdByContentId = new Map<string, Uint8Array>(
      built.expression.input.photos.map((photo): [string, Uint8Array] => [photo.contentId, uuidToBytes(tagBlobId(capsuleBytes, PHOTO_BLOB_TAG_BASE + photo.ordinal))]),
    )
    fresh()
    const content = await encodeContentManifest({ expression: built.expression, capsuleId: capsuleBytes, candidateId, blobIdByContentId })
    if (content.ok) owned.push(content.bytes) // own before the post-await fence/validation
    fresh()
    if (!content.ok) throw new SealCapsuleError('content')
    // The manifest encoder caps at 65536 while the AEAD usable plaintext is
    // 65503; refuse explicitly rather than reaching an OVERSIZED crypto error.
    if (content.bytes.length + limits.artifactOverhead > limits.contentCiphertext) throw new SealCapsuleError('content')

    capsuleKeyset = generateCapsuleKeyset()

    let recognitionCiphertext: Uint8Array
    try {
      recognitionCiphertext = await withFresh(() => encryptArtifact(
        capsuleKeyset!,
        artifactAad({ capsuleId, blobId: recognitionBlobId, kind: 'RECOGNITION_MANIFEST', ordinal: -1, senderUserId: ownedFreeze.ownerId, recipientUserId: ownedFreeze.recipient.userId }),
        recognition.bytes,
        'RECOGNITION_MANIFEST',
      ))
    } catch (error) {
      throw mapFailure(error, 'recognition')
    }

    let contentCiphertext: Uint8Array
    try {
      contentCiphertext = await withFresh(() => encryptArtifact(
        capsuleKeyset!,
        artifactAad({ capsuleId, blobId: contentBlobId, kind: 'CONTENT_MANIFEST', ordinal: -1, senderUserId: ownedFreeze.ownerId, recipientUserId: ownedFreeze.recipient.userId }),
        content.bytes,
        'CONTENT_MANIFEST',
      ))
    } catch (error) {
      throw mapFailure(error, 'content')
    }

    const recognitionDigest = await withFresh(() => sha256(recognitionCiphertext))
    const contentDigest = await withFresh(() => sha256(contentCiphertext))
    const artifacts: PublishStatementArtifact[] = [
      { blobId: uuidToBytes(recognitionBlobId), kind: 'RECOGNITION_MANIFEST', ordinal: -1, ciphertextSize: recognitionCiphertext.length, ciphertextSha256: recognitionDigest },
      { blobId: uuidToBytes(contentBlobId), kind: 'CONTENT_MANIFEST', ordinal: -1, ciphertextSize: contentCiphertext.length, ciphertextSha256: contentDigest },
    ]
    const blobs: SealedCapsuleBlob[] = [
      { blobId: recognitionBlobId, kind: 'RECOGNITION_MANIFEST', ordinal: -1, ciphertext: recognitionCiphertext, ciphertextSize: recognitionCiphertext.length, ciphertextSha256: recognitionDigest },
      { blobId: contentBlobId, kind: 'CONTENT_MANIFEST', ordinal: -1, ciphertext: contentCiphertext, ciphertextSize: contentCiphertext.length, ciphertextSha256: contentDigest },
    ]

    for (const photo of built.expression.input.photos) {
      const bytes = ownedPhotoBytes.get(photo.contentId)
      if (!bytes) throw new SealCapsuleError('photo')
      const blobId = tagBlobId(capsuleBytes, PHOTO_BLOB_TAG_BASE + photo.ordinal)
      let ciphertext: Uint8Array
      try {
        ciphertext = await withFresh(() => encryptArtifact(
          capsuleKeyset!,
          artifactAad({ capsuleId, blobId, kind: 'PHOTO', ordinal: photo.ordinal, senderUserId: ownedFreeze.ownerId, recipientUserId: ownedFreeze.recipient.userId }),
          bytes,
          'PHOTO',
        ))
      } catch (error) {
        throw mapFailure(error, 'photo')
      }
      const digest = await withFresh(() => sha256(ciphertext))
      artifacts.push({ blobId: uuidToBytes(blobId), kind: 'PHOTO', ordinal: photo.ordinal, ciphertextSize: ciphertext.length, ciphertextSha256: digest })
      blobs.push({ blobId, kind: 'PHOTO', ordinal: photo.ordinal, ciphertext, ciphertextSize: ciphertext.length, ciphertextSha256: digest })
    }

    let statement: Uint8Array
    try {
      statement = buildPublishStatement({
        capsuleId: capsuleBytes,
        senderUserId: senderBytes,
        recipientUserId: recipientUserBytes,
        senderKeyBundleId: senderBundleBytes,
        recipientKeyBundleId: recipientBundleBytes,
        createdAtEpochSeconds,
        artifacts,
      })
    } catch (error) {
      throw mapFailure(error, 'statement')
    }
    let signature: Uint8Array
    try {
      signature = await withFresh(() => caps.sign(statement))
    } catch (error) {
      throw mapFailure(error, 'sign')
    }
    const statementSha256 = await withFresh(() => sha256(statement))

    let envelopePlaintext: Uint8Array
    try {
      envelopePlaintext = buildRecipientEnvelopePlaintext({
        capsuleId: capsuleBytes,
        senderUserId: senderBytes,
        recipientUserId: recipientUserBytes,
        senderKeyBundleId: senderBundleBytes,
        recipientKeyBundleId: recipientBundleBytes,
        capsuleAeadKeyset: capsuleKeyset,
        publishStatementSha256: statementSha256,
      })
    } catch (error) {
      throw mapFailure(error, 'envelope')
    }
    owned.push(envelopePlaintext)

    let envelopeCiphertext: Uint8Array
    try {
      envelopeCiphertext = await withFresh(() => sealRecipientEnvelope(
        recipientPublic,
        recipientEnvelopeInfo({ capsuleId, senderUserId: ownedFreeze.ownerId, recipientUserId: ownedFreeze.recipient.userId, recipientKeyBundleId: ownedFreeze.recipient.keyBundleId }),
        envelopePlaintext,
      ))
    } catch (error) {
      throw mapFailure(error, 'envelope')
    }

    fresh()
    return {
      capsuleId,
      senderUserId: ownedFreeze.ownerId,
      recipientUserId: ownedFreeze.recipient.userId,
      senderKeyBundleId: view.bundleId,
      recipientKeyBundleId: ownedFreeze.recipient.keyBundleId,
      statement,
      signature,
      statementSha256,
      envelopeCiphertext,
      blobs,
    }
  } finally {
    // Owned secrets only: the ephemeral capsule keyset and every owned plaintext
    // copy. The caller's session/capabilities are never dropped or locked here,
    // and the returned ciphertext/public metadata stays usable.
    if (capsuleKeyset !== null) capsuleKeyset.fill(0)
    for (const bytes of owned) bytes.fill(0)
  }
}
