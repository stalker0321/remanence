/**
 * T291 TEST-ONLY sender capsule assembly.
 *
 * Joins the ACCEPTED sender primitives in one place for the browser
 * round-trip fixture: GENEX04 expression -> ContentManifest + RecognitionManifest
 * -> one fresh capsule keyset -> AEAD recognition/content/normalized-JPEG photos
 * -> exact PublishStatement -> real Ed25519 signature -> raw statement SHA-256
 * bound envelope plaintext -> real HPKE seal. It is NOT the production sender
 * interface, grant, admission, recognition, or publish path; there is no
 * production state/clock/account policy here. The caller owns every secret and
 * MUST call `wipe()` (ephemeral capsule keyset + recognition/content/envelope
 * plaintexts). Synthetic inputs only; no real media, provider, or recognition.
 */
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { encodeContentManifest } from '../src/collage/manifest'
import type { ResolvedExpression } from '../src/collage/model'
import { encodeRecognitionManifest } from '../src/collage/recognitionManifest'
import { encryptArtifact } from '../src/crypto/artifactEncrypt'
import { generateCapsuleKeyset } from '../src/crypto/capsuleKeyset'
import { sealRecipientEnvelope } from '../src/crypto/hpkeSeal'
import { signPublishStatement } from '../src/crypto/publish'
import { buildRecipientEnvelopePlaintext } from '../src/crypto/recipientEnvelope'
import { arrayBuffer, bytesToHex } from '../src/protocol/bytes'
import { artifactAad, recipientEnvelopeInfo } from '../src/protocol/context'
import { buildPublishStatement, type PublishStatementArtifact } from '../src/protocol/publishStatement'
import { uuidToBytes } from '../src/protocol/uuid'
import type { IncomingBlob, IncomingCapsule } from '../src/sync/incoming'

/** Android `CapsulePublisher.RECOGNITION_BLOB_BYTE` / `CONTENT_BLOB_BYTE`. */
const RECOGNITION_BLOB_BYTE = 0x01
const CONTENT_BLOB_BYTE = 0x02
/** Android `CapsulePhotoIdentity.PHOTO_BLOB_TAG_BASE`. */
const PHOTO_BLOB_TAG_BASE = 0x10

function formatUuid(bytes: Uint8Array): string {
  const hex = bytesToHex(bytes)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * Android `CapsulePublisher.blob(tag)` / `CapsulePhotoIdentity.photoBlobId`:
 * the capsule UUID with byte 0 replaced by the tag byte (recognition 0x01,
 * content 0x02, photo 0x10 + ordinal).
 */
export function deriveCapsuleBlobId(capsuleId: string, tagByte: number): string {
  const bytes = uuidToBytes(capsuleId)
  bytes[0] = tagByte
  return formatUuid(bytes)
}

export interface SenderCapsuleInput {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  senderHandle: string
  createdAtEpochSeconds: bigint
  candidateId: string
  expression: ResolvedExpression
  photoBytesByContentId: ReadonlyMap<string, Uint8Array>
  /** Explicit synthetic opaque bytes; NOT a real FRONT recognition fingerprint. */
  recognitionFingerprint: Uint8Array
  senderSigningPrivateKeyset: Uint8Array
  recipientHpkePublicKeyset: Uint8Array
}

export interface SenderCapsule {
  capsule: IncomingCapsule
  statement: Uint8Array
  signature: Uint8Array
  envelopeCiphertext: Uint8Array
  /** Caller-owned ephemeral keyset; zero it via `wipe()`. */
  capsuleKeyset: Uint8Array
  recognition: { blobId: string; ciphertext: Uint8Array; plaintext: Uint8Array; aad: Uint8Array }
  content: { blobId: string; ciphertext: Uint8Array }
  photos: Array<{ contentId: string; ordinal: number; blobId: string; ciphertext: Uint8Array }>
  wipe(): void
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

export async function assembleSenderCapsule(input: SenderCapsuleInput): Promise<SenderCapsule> {
  const {
    capsuleId,
    senderUserId,
    recipientUserId,
    senderKeyBundleId,
    recipientKeyBundleId,
    senderHandle,
    createdAtEpochSeconds,
    candidateId,
    expression,
    photoBytesByContentId,
    recognitionFingerprint,
    senderSigningPrivateKeyset,
    recipientHpkePublicKeyset,
  } = input

  const capsuleBytes = uuidToBytes(capsuleId)
  const plains: Uint8Array[] = []
  const capsuleKeyset = generateCapsuleKeyset()

  try {
    const recognitionBlobId = deriveCapsuleBlobId(capsuleId, RECOGNITION_BLOB_BYTE)
    const contentBlobId = deriveCapsuleBlobId(capsuleId, CONTENT_BLOB_BYTE)

    // 1. Recognition manifest: explicit synthetic opaque fingerprint only.
    const recognitionEncoded = encodeRecognitionManifest({
      capsuleId: capsuleBytes,
      senderHandleSnapshot: senderHandle,
      createdAtEpochSeconds,
      placeLabel: null,
      frontFingerprint: recognitionFingerprint,
    })
    if (!recognitionEncoded.ok) throw new Error(`recognition manifest refused: ${recognitionEncoded.reason}`)
    plains.push(recognitionEncoded.bytes)
    const recognitionAad = artifactAad({
      capsuleId,
      blobId: recognitionBlobId,
      kind: 'RECOGNITION_MANIFEST',
      ordinal: -1,
      senderUserId,
      recipientUserId,
    })
    const recognitionCiphertext = await encryptArtifact(capsuleKeyset, recognitionAad, recognitionEncoded.bytes, 'RECOGNITION_MANIFEST')

    // 2. Content manifest over the exact authored photo set.
    const blobIdByContentId = new Map<string, Uint8Array>(
      expression.input.photos.map((photo): [string, Uint8Array] => [photo.contentId, uuidToBytes(deriveCapsuleBlobId(capsuleId, PHOTO_BLOB_TAG_BASE + photo.ordinal))]),
    )
    const contentEncoded = await encodeContentManifest({ expression, capsuleId: capsuleBytes, candidateId, blobIdByContentId })
    if (!contentEncoded.ok) throw new Error(`content manifest refused: ${contentEncoded.reason}`)
    plains.push(contentEncoded.bytes)
    const contentAad = artifactAad({ capsuleId, blobId: contentBlobId, kind: 'CONTENT_MANIFEST', ordinal: -1, senderUserId, recipientUserId })
    const contentCiphertext = await encryptArtifact(capsuleKeyset, contentAad, contentEncoded.bytes, 'CONTENT_MANIFEST')

    const artifacts: PublishStatementArtifact[] = [
      { blobId: uuidToBytes(recognitionBlobId), kind: 'RECOGNITION_MANIFEST', ordinal: -1, ciphertextSize: recognitionCiphertext.length, ciphertextSha256: await sha256(recognitionCiphertext) },
      { blobId: uuidToBytes(contentBlobId), kind: 'CONTENT_MANIFEST', ordinal: -1, ciphertextSize: contentCiphertext.length, ciphertextSha256: await sha256(contentCiphertext) },
    ]
    const blobDeclarations: IncomingBlob[] = [
      { blobId: recognitionBlobId, kind: 'RECOGNITION_MANIFEST', ordinal: null, ciphertextSize: recognitionCiphertext.length, ciphertextSha256: b64(await sha256(recognitionCiphertext)) },
      { blobId: contentBlobId, kind: 'CONTENT_MANIFEST', ordinal: null, ciphertextSize: contentCiphertext.length, ciphertextSha256: b64(await sha256(contentCiphertext)) },
    ]

    // 3. Photos: each normalized JPEG under its own PHOTO-ordinal AAD.
    const photos: SenderCapsule['photos'] = []
    for (const photo of expression.input.photos) {
      const bytes = photoBytesByContentId.get(photo.contentId)
      if (!bytes) throw new Error(`missing normalized bytes for ${photo.contentId}`)
      const blobId = deriveCapsuleBlobId(capsuleId, PHOTO_BLOB_TAG_BASE + photo.ordinal)
      const aad = artifactAad({ capsuleId, blobId, kind: 'PHOTO', ordinal: photo.ordinal, senderUserId, recipientUserId })
      const ciphertext = await encryptArtifact(capsuleKeyset, aad, bytes, 'PHOTO')
      const digest = await sha256(ciphertext)
      artifacts.push({ blobId: uuidToBytes(blobId), kind: 'PHOTO', ordinal: photo.ordinal, ciphertextSize: ciphertext.length, ciphertextSha256: digest })
      blobDeclarations.push({ blobId, kind: 'PHOTO', ordinal: photo.ordinal, ciphertextSize: ciphertext.length, ciphertextSha256: b64(digest) })
      photos.push({ contentId: photo.contentId, ordinal: photo.ordinal, blobId, ciphertext })
    }

    // 4. Exact statement + real signature.
    const statement = buildPublishStatement({
      capsuleId: capsuleBytes,
      senderUserId: uuidToBytes(senderUserId),
      recipientUserId: uuidToBytes(recipientUserId),
      senderKeyBundleId: uuidToBytes(senderKeyBundleId),
      recipientKeyBundleId: uuidToBytes(recipientKeyBundleId),
      createdAtEpochSeconds,
      artifacts,
    })
    const signature = await signPublishStatement(senderSigningPrivateKeyset, statement)
    const statementSha256 = await sha256(statement)

    // 5. Envelope plaintext (raw statement SHA-256) + real HPKE seal.
    const envelopePlaintext = buildRecipientEnvelopePlaintext({
      capsuleId: capsuleBytes,
      senderUserId: uuidToBytes(senderUserId),
      recipientUserId: uuidToBytes(recipientUserId),
      senderKeyBundleId: uuidToBytes(senderKeyBundleId),
      recipientKeyBundleId: uuidToBytes(recipientKeyBundleId),
      capsuleAeadKeyset: capsuleKeyset,
      publishStatementSha256: statementSha256,
    })
    plains.push(envelopePlaintext)
    const envelopeCiphertext = await sealRecipientEnvelope(
      recipientHpkePublicKeyset,
      recipientEnvelopeInfo({ capsuleId, senderUserId, recipientUserId, recipientKeyBundleId }),
      envelopePlaintext,
    )

    const capsule: IncomingCapsule = {
      capsuleId,
      senderUserId,
      recipientUserId,
      senderKeyBundleId,
      recipientKeyBundleId,
      envelopeCiphertext,
      statementSha256,
      blobs: blobDeclarations,
    }

    let wiped = false
    return {
      capsule,
      statement,
      signature,
      envelopeCiphertext,
      capsuleKeyset,
      recognition: { blobId: recognitionBlobId, ciphertext: recognitionCiphertext, plaintext: recognitionEncoded.bytes, aad: recognitionAad },
      content: { blobId: contentBlobId, ciphertext: contentCiphertext },
      photos,
      wipe(): void {
        if (wiped) return
        wiped = true
        capsuleKeyset.fill(0)
        for (const plain of plains) plain.fill(0)
      },
    }
  } catch (error) {
    capsuleKeyset.fill(0)
    for (const plain of plains) plain.fill(0)
    throw error
  }
}
