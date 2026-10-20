import type { MemoryKeys } from '../auth/memoryKeys'
import type { KeyCapabilities } from '../crypto/keyOwner'
import { fieldBytes, fieldNumber, readMessage } from '../protocol/proto'
import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { artifactAad, recipientEnvelopeInfo } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { uuidToBytes } from '../protocol/uuid'
import type { ScanGrant } from '../scan/grant'
import type { SessionStore } from '../session/session'
import type { IncomingCapsule } from '../sync/incoming'
import { CollageError } from './errors'
import { jpegSize } from './jpeg'
import { prepare, type Scene, type TextMeasurer } from './prepare'
import { equalBytes } from './styles'
import { parseContentManifest } from './wire'

export interface SealedBlob {
  blobId: string
  ciphertext: Uint8Array
}

export interface SealedPhoto extends SealedBlob {
  ordinal: number
}

export interface HeldArtwork {
  bytes: Uint8Array
  width: number
  height: number
}

export interface OpenedPostcard {
  scene: Scene
  photos: Map<string, Uint8Array>
  artwork: Uint8Array | null
}

/**
 * Decrypts one granted postcard and prepares the sealed sheet.
 * A rejection leaves the session where it was and returns no scene.
 * JS callers that build the ciphertext must not describe it as Android output.
 */
export async function openGrantedPostcard(
  session: SessionStore,
  keys: MemoryKeys,
  grant: ScanGrant,
  capsule: IncomingCapsule,
  content: SealedBlob,
  photos: readonly SealedPhoto[],
  measurer: TextMeasurer,
  artwork: HeldArtwork | null = null,
  signal?: AbortSignal,
): Promise<OpenedPostcard> {
  const view = session.getSnapshot()
  const caps = keys.capabilities(view)
  if (
    view.phase !== 'postcard-matched'
    || caps === null
    || caps.accountId !== view.accountId
    || view.accountId !== capsule.recipientUserId
    || !grant.allows(view, capsule.capsuleId)
  ) {
    throw new CollageError('GRANT')
  }
  const generation = view.generation
  const fresh = (): KeyCapabilities => {
    const next = session.getSnapshot()
    const current = keys.capabilities(next)
    if (
      signal?.aborted
      || next.generation !== generation
      || next.phase !== 'postcard-matched'
      || current !== caps
      || !grant.allows(next, capsule.capsuleId)
    ) {
      throw new CollageError('STALE')
    }
    return current
  }
  const opened = await fresh().openEnvelope(recipientEnvelopeInfo({
    capsuleId: capsule.capsuleId,
    senderUserId: capsule.senderUserId,
    recipientUserId: capsule.recipientUserId,
    recipientKeyBundleId: capsule.recipientKeyBundleId,
  }), capsule.envelopeCiphertext)
  let keyset: Uint8Array | null = null
  const plains: Uint8Array[] = []
  try {
    fresh()
    const envelope = parseEnvelope(opened)
    if (
      !equalBytes(envelope.capsuleId, uuidToBytes(capsule.capsuleId))
      || !equalBytes(envelope.senderUserId, uuidToBytes(capsule.senderUserId))
      || !equalBytes(envelope.recipientUserId, uuidToBytes(capsule.recipientUserId))
      || !equalBytes(envelope.senderBundleId, uuidToBytes(capsule.senderKeyBundleId))
      || !equalBytes(envelope.recipientBundleId, uuidToBytes(capsule.recipientKeyBundleId))
      || !equalBytes(envelope.statementSha256, capsule.statementSha256)
    ) {
      throw new CollageError('INTEGRITY')
    }
    keyset = envelope.keyset.slice()
    envelope.keyset.fill(0)
    const contentPlain = await fresh().decryptArtifact(
      keyset,
      artifactAad({
        capsuleId: capsule.capsuleId,
        blobId: content.blobId,
        kind: 'CONTENT_MANIFEST',
        ordinal: -1,
        senderUserId: capsule.senderUserId,
        recipientUserId: capsule.recipientUserId,
      }),
      content.ciphertext,
      'CONTENT_MANIFEST',
    )
    plains.push(contentPlain)
    fresh()
    const manifest = await parseContentManifest(contentPlain)
    fresh()
    if (!equalBytes(manifest.capsuleId, uuidToBytes(capsule.capsuleId))) throw new CollageError('INTEGRITY')
    const contentBlob = capsule.blobs.find((blob) => blob.blobId === content.blobId && blob.kind === 'CONTENT_MANIFEST')
    if (!contentBlob) throw new CollageError('INTEGRITY')
    const photoPlain = new Map<string, Uint8Array>()
    for (const source of manifest.photos) {
      const blobId = formatUuid(source.blobId)
      const sealed = photos.find((photo) => photo.blobId === blobId && photo.ordinal === source.ordinal)
      const declared = capsule.blobs.find((blob) => blob.blobId === blobId && blob.kind === 'PHOTO' && blob.ordinal === source.ordinal)
      if (!sealed || !declared) throw new CollageError('INTEGRITY')
      const plain = await fresh().decryptArtifact(
        keyset,
        artifactAad({
          capsuleId: capsule.capsuleId,
          blobId,
          kind: 'PHOTO',
          ordinal: source.ordinal,
          senderUserId: capsule.senderUserId,
          recipientUserId: capsule.recipientUserId,
        }),
        sealed.ciphertext,
        'PHOTO',
      )
      plains.push(plain)
      const size = jpegSize(plain)
      const authored = manifest.expression.input.photos[source.ordinal]
      if (!size || !authored || size.width !== source.width || size.height !== source.height || size.width !== authored.widthPx || size.height !== authored.heightPx) {
        throw new CollageError('INTEGRITY')
      }
      await assertDecodedSize(plain, size.width, size.height)
      fresh()
      photoPlain.set(authored.contentId, plain)
    }
    const cover = await coverFor(manifest, artwork)
    fresh()
    const prepared = await prepare(
      manifest.expression,
      manifest.note,
      manifest.track,
      new Map(manifest.expression.input.photos.map((photo) => [photo.contentId, { width: photo.widthPx, height: photo.heightPx }])),
      measurer,
      cover,
    )
    fresh()
    if (prepared.kind !== 'ready') throw new CollageError('REJECTED')
    const kept = new Map<string, Uint8Array>()
    for (const [id, bytes] of photoPlain) kept.set(id, bytes.slice())
    return { scene: prepared.scene, photos: kept, artwork: cover && artwork ? artwork.bytes.slice() : null }
  } catch (error) {
    if (error instanceof CollageError || error instanceof ProtocolError) throw error
    throw new CollageError('INTEGRITY')
  } finally {
    opened.fill(0)
    keyset?.fill(0)
    for (const plain of plains) plain.fill(0)
  }
}

async function coverFor(manifest: Awaited<ReturnType<typeof parseContentManifest>>, artwork: HeldArtwork | null): Promise<{ width: number; height: number } | null> {
  const descriptor = manifest.expression.musicRegion?.artwork
  if (!descriptor || !artwork) return null
  if (artwork.width !== descriptor.width || artwork.height !== descriptor.height) return null
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(artwork.bytes)))
  if (!equalBytes(digest, descriptor.sha256)) return null
  return { width: artwork.width, height: artwork.height }
}

async function assertDecodedSize(bytes: Uint8Array, width: number, height: number): Promise<void> {
  const decode = globalThis.createImageBitmap
  if (typeof decode !== 'function') return
  try {
    const image = await decode(new Blob([arrayBuffer(bytes)], { type: 'image/jpeg' }))
    const matches = image.width === width && image.height === height
    image.close()
    if (!matches) throw new CollageError('INTEGRITY')
  } catch (error) {
    if (error instanceof CollageError) throw error
    throw new CollageError('INTEGRITY')
  }
}

function parseEnvelope(bytes: Uint8Array): {
  capsuleId: Uint8Array
  senderUserId: Uint8Array
  recipientUserId: Uint8Array
  senderBundleId: Uint8Array
  recipientBundleId: Uint8Array
  keyset: Uint8Array
  statementSha256: Uint8Array
} {
  const fields = readMessage(bytes, [1, 2, 3, 4, 5, 6, 7, 8])
  if (fieldNumber(fields, 1) !== 1) throw new ProtocolError('UNKNOWN_VERSION')
  const capsuleId = fieldBytes(fields, 2)
  const senderUserId = fieldBytes(fields, 3)
  const recipientUserId = fieldBytes(fields, 4)
  const senderBundleId = fieldBytes(fields, 5)
  const recipientBundleId = fieldBytes(fields, 6)
  const statementSha256 = fieldBytes(fields, 8)
  if ([capsuleId, senderUserId, recipientUserId, senderBundleId, recipientBundleId].some((value) => value.length !== 16) || statementSha256.length !== 32) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  return {
    capsuleId,
    senderUserId,
    recipientUserId,
    senderBundleId,
    recipientBundleId,
    keyset: fieldBytes(fields, 7),
    statementSha256,
  }
}

function formatUuid(bytes: Uint8Array): string {
  const hex = bytesToHex(bytes)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
