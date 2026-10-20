import type { MemoryKeys } from '../auth/memoryKeys'
import type { ApiTransport } from '../auth/http'
import { CollageError } from '../collage/errors'
import { showGrantedPostcard, type PresentationMemory } from '../collage/memory'
import { openGrantedPostcard, type OpenedPostcard, type SealedPhoto } from '../collage/open'
import type { TextMeasurer } from '../collage/prepare'
import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { SessionError, type SessionStore } from '../session/session'
import type { IncomingBlob, IncomingCapsule, IncomingCatalog } from '../sync/incoming'
import type { StillCapture } from './camera'
import { ScanError } from './errors'
import { admitScan, considerCapture, type RecognitionEngine } from './grant'
import type { RecognitionIndex } from './recognitionIndex'
import { admitFirstOpen } from './admission'

export interface CiphertextPort {
  read(item: IncomingCapsule, blobId: string, signal: AbortSignal): Promise<Uint8Array>
}

/**
 * Incoming list, local still, prepare, server first-open, owned presentation.
 * An empty list, a missing body, or a changed session never draws a sheet.
 * Callers that supply JS ciphertext must not call it Android output.
 */
export async function receivePostcard(
  session: SessionStore,
  keys: MemoryKeys,
  memory: PresentationMemory,
  engine: RecognitionEngine,
  capture: StillCapture,
  catalog: IncomingCatalog,
  blobs: CiphertextPort,
  measurer: TextMeasurer,
  signal: AbortSignal,
  index?: RecognitionIndex,
  transport?: ApiTransport,
  onAdmission?: () => void,
): Promise<void> {
  if (signal.aborted) throw new ScanError('CANCELLED')
  const view = session.getSnapshot()
  const generation = view.generation
  if (view.phase !== 'keys-unlocked' || view.accountId === null) throw new ScanError('GRANT_MISMATCH')
  if (catalog.items.length === 0) throw new ScanError('EMPTY')
  const grant = await considerCapture(view, engine, capture, catalog, index ? { index } : undefined)
  if (signal.aborted) throw new ScanError('CANCELLED')
  stillUnlocked(session, generation)
  const item = catalog.items.find((candidate) => candidate.capsuleId === grant.capsuleId)
  if (!item) throw new ScanError('BODY_MISSING')
  const content = item.blobs.find((blob) => blob.kind === 'CONTENT_MANIFEST')
  const photos = item.blobs.filter((blob) => blob.kind === 'PHOTO' && blob.ordinal !== null)
  if (!content || photos.length === 0 || item.envelopeCiphertext.length === 0) throw new ScanError('BODY_MISSING')
  const downloaded: Uint8Array[] = []
  let matchedGeneration: number | null = null
  let opened: OpenedPostcard | null = null
  try {
    const contentBytes = await readChecked(blobs, item, content, signal)
    downloaded.push(contentBytes)
    stillUnlocked(session, generation)
    const sealedPhotos: SealedPhoto[] = []
    for (const photo of [...photos].sort((left, right) => (left.ordinal ?? 0) - (right.ordinal ?? 0))) {
      if (signal.aborted) throw new ScanError('CANCELLED')
      const bytes = await readChecked(blobs, item, photo, signal)
      downloaded.push(bytes)
      stillUnlocked(session, generation)
      sealedPhotos.push({ blobId: photo.blobId, ordinal: photo.ordinal ?? 0, ciphertext: bytes })
    }
    if (signal.aborted) throw new ScanError('CANCELLED')
    const caps = keys.capabilities(session.getSnapshot())
    if (caps === null || caps.accountId !== view.accountId || caps.bundleId !== item.recipientKeyBundleId
      || view.bundleId !== item.recipientKeyBundleId) throw new ScanError('GRANT_MISMATCH')
    admitScan(session, grant)
    // matchPostcard advances exactly this generation once. A synchronous
    // subscriber may already have replaced it by the time admitScan returns.
    matchedGeneration = generation + 1
    const currentToken = () => {
      if (signal.aborted) throw new ScanError('CANCELLED')
      const next = session.getSnapshot()
      if (next.generation !== matchedGeneration || next.phase !== 'postcard-matched'
        || next.accountId !== view.accountId || next.bundleId !== view.bundleId
        || keys.capabilities(next) !== caps) throw new ScanError('STALE')
      const secrets = session.secretsFor(next.generation)
      if (!secrets) throw new ScanError('STALE')
      return secrets.accessToken
    }
    currentToken()
    opened = await openGrantedPostcard(session, keys, grant, item, {
      blobId: content.blobId,
      ciphertext: contentBytes,
    }, sealedPhotos, measurer, null, signal)
    currentToken()
    onAdmission?.()
    currentToken()
    await admitFirstOpen(transport, item.capsuleId, currentToken, signal)
    currentToken()
    showGrantedPostcard(session, memory, opened)
  } catch (error) {
    if (matchedGeneration !== null) session.retryMatchedPostcard(matchedGeneration)
    throw error
  } finally {
    if (opened) {
      for (const bytes of opened.photos.values()) bytes.fill(0)
      opened.artwork?.fill(0)
    }
    for (const bytes of downloaded) bytes.fill(0)
  }
}

async function readChecked(
  blobs: CiphertextPort,
  item: IncomingCapsule,
  blob: IncomingBlob,
  signal: AbortSignal,
): Promise<Uint8Array> {
  const body = await blobs.read(item, blob.blobId, signal)
  try {
    if (signal.aborted) throw new ScanError('CANCELLED')
    if (body.length !== blob.ciphertextSize) throw new CollageError('INTEGRITY')
    const digestBytes = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(body)))
    if (signal.aborted) throw new ScanError('CANCELLED')
    if (bytesToHex(digestBytes) !== blob.ciphertextSha256 && b64(digestBytes) !== blob.ciphertextSha256) throw new CollageError('INTEGRITY')
    return body
  } catch (error) {
    body.fill(0)
    throw error
  }
}

function stillUnlocked(session: SessionStore, generation: number): void {
  try {
    session.bind(generation)()
  } catch (error) {
    if (error instanceof SessionError) throw new ScanError('STALE')
    throw error
  }
  if (session.getSnapshot().phase !== 'keys-unlocked') throw new ScanError('STALE')
}
