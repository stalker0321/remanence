import type { MemoryKeys } from '../auth/memoryKeys'
import type { ApiTransport, ByteTransport } from '../auth/http'
import { artifactAad, recipientEnvelopeInfo } from '../protocol/context'
import { fieldBytes, fieldNumber, readMessage } from '../protocol/proto'
import { bytesToHex } from '../protocol/bytes'
import { uuidToBytes } from '../protocol/uuid'
import type { SessionStore } from '../session/session'
import { SyncError } from '../sync/errors'
import { downloadCiphertext, type IncomingCatalog } from '../sync/incoming'
import { readRecognitionManifest } from '../collage/readRecognitionManifest'
import { parseSiftFingerprint, type SiftFingerprint } from './siftFingerprint'

/**
 * T328 — owner-fenced TRANSIENT pre-grant recognition index for ONE supplied
 * `fetchIncoming` catalog page (existing maximum 100 items). For each item it
 * performs the accepted envelope open (`recipientEnvelopeInfo` +
 * `caps.openEnvelope`), the strict envelope schema/binding checks mirrored
 * from `collage/open.ts:188-223`, the existing `downloadCiphertext`
 * size/sha256 validation for the RECOGNITION_MANIFEST blob, the exact
 * `artifactAad` decrypt (`kind` RECOGNITION_MANIFEST, ordinal -1), the strict
 * v2 manifest reader, and the accepted `parseSiftFingerprint` — producing
 * per-capsule format-3 fingerprints for a future matcher.
 *
 * This module NEVER downloads or decrypts content/photos, never grants,
 * never calls admitScan/matchPostcard/show, and never holds account private
 * keys, capsule keysets, or decrypted body material in the returned index.
 * Reads are sequential; ANY integrity/structural error fails the WHOLE
 * build (no partial return, no silent skip). The build runs only at
 * `keys-unlocked` and revalidates owner/account/bundle/generation/capsule
 * capability identity plus the abort signal before and after every await.
 *
 * Plaintext/keyset/ciphertext/manifest/wire buffers produced along the way
 * are registered as owned IMMEDIATELY (before further stale checks) and
 * wiped on every failure/abort/lock path; successful descriptor rows are
 * owned by the index and wiped by `dispose()`, which the caller must invoke
 * in a finally (match consumption or refusal alike). Nothing is logged,
 * cached durably, or persisted. The supplied page's hasMore/nextCursor are
 * preserved verbatim; a unique match on this page is NOT a global-uniqueness
 * claim.
 */

export type RecognitionIndexErrorCode = 'unavailable' | 'invalid' | 'stale'

/** Bounded, redacted refusal. Never carries ids/keys/plaintext/hints. */
export class RecognitionIndexError extends Error {
  constructor(readonly code: RecognitionIndexErrorCode) {
    super(code)
    this.name = 'RecognitionIndexError'
  }
}

function refuse(code: RecognitionIndexErrorCode): never {
  throw new RecognitionIndexError(code)
}

export interface RecognitionIndexEntry {
  readonly capsuleId: string
  readonly fingerprint: SiftFingerprint
}

interface OwnerFence {
  session: SessionStore
  keys: MemoryKeys
  caps: object
  accountId: string
  bundleId: string
  generation: number
  signal: AbortSignal | undefined
  disposed: boolean
}

function fenceCurrent(fence: OwnerFence): boolean {
  if (fence.disposed) return false
  if (fence.signal?.aborted === true) return false
  const view = fence.session.getSnapshot()
  if (view.phase !== 'keys-unlocked') return false
  if (view.accountId !== fence.accountId || view.bundleId !== fence.bundleId) return false
  if (view.generation !== fence.generation) return false
  return fence.keys.capabilities(view) === fence.caps
}

export class RecognitionIndex {
  readonly hasMore: boolean
  readonly nextCursor: string | null
  #entries: RecognitionIndexEntry[]
  #fence: OwnerFence
  #owned: Uint8Array[]

  constructor(
    entries: RecognitionIndexEntry[],
    owned: Uint8Array[],
    fence: OwnerFence,
    page: { hasMore: boolean; nextCursor: string | null },
  ) {
    this.#entries = entries
    this.#owned = owned
    this.#fence = fence
    this.hasMore = page.hasMore
    this.nextCursor = page.nextCursor
  }

  get disposed(): boolean {
    return this.#fence.disposed
  }

  /** Refuses (typed stale) once the owner/session/caps/signal context dies. */
  entries(): readonly RecognitionIndexEntry[] {
    if (!fenceCurrent(this.#fence)) refuse('stale')
    return this.#entries
  }

  /** Idempotent. Wipes every owned descriptor row and temporary buffer. */
  dispose(): void {
    if (this.#fence.disposed) return
    this.#fence.disposed = true
    for (const entry of this.#entries) {
      for (const row of entry.fingerprint.quantizedSiftDescriptors) row.fill(0)
      entry.fingerprint.quantizedSiftDescriptors.length = 0
      entry.fingerprint.keypoints.length = 0
    }
    this.#entries = []
    for (const buffer of this.#owned) buffer.fill(0)
    this.#owned = []
  }
}

interface SnapshotItem {
  capsuleId: string
  senderUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  envelopeCiphertext: Uint8Array
  statementSha256: Uint8Array
  recognitionBlobId: string
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0)
  return diff === 0
}

function uuidText(bytes: Uint8Array): string {
  const hex = bytesToHex(bytes)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export interface BuildRecognitionIndexDeps {
  readonly session: SessionStore
  readonly keys: MemoryKeys
  readonly transport: ApiTransport & ByteTransport
  readonly catalog: IncomingCatalog
  readonly signal?: AbortSignal
}

export async function buildRecognitionIndex(deps: BuildRecognitionIndexDeps): Promise<RecognitionIndex> {
  const { session, keys, transport, catalog, signal } = deps
  if (signal?.aborted === true) refuse('stale')
  const view = session.getSnapshot()
  if (view.phase !== 'keys-unlocked' || view.accountId === null || view.bundleId === null) refuse('unavailable')
  const caps = keys.capabilities(view)
  if (caps === null) refuse('unavailable')
  const fence: OwnerFence = {
    session,
    keys,
    caps,
    accountId: view.accountId,
    bundleId: view.bundleId,
    generation: view.generation,
    signal,
    disposed: false,
  }
  const fenceOrRefuse = (): void => {
    if (!fenceCurrent(fence)) refuse('stale')
  }

  // Snapshot routing/metadata BEFORE the first await (strings/refs only;
  // caller ciphertext and metadata are never mutated or retained past build).
  const seen = new Set<string>()
  const snapshot: SnapshotItem[] = []
  for (const item of catalog.items) {
    if (item.recipientUserId !== fence.accountId || item.recipientKeyBundleId !== fence.bundleId) refuse('invalid')
    if (seen.has(item.capsuleId)) refuse('invalid')
    seen.add(item.capsuleId)
    const recognition = item.blobs.filter((blob) => blob.kind === 'RECOGNITION_MANIFEST')
    if (recognition.length !== 1 || recognition[0]?.ordinal !== null) refuse('invalid')
    snapshot.push({
      capsuleId: item.capsuleId,
      senderUserId: item.senderUserId,
      senderKeyBundleId: item.senderKeyBundleId,
      recipientKeyBundleId: item.recipientKeyBundleId,
      envelopeCiphertext: item.envelopeCiphertext,
      statementSha256: item.statementSha256,
      recognitionBlobId: recognition[0]!.blobId,
    })
  }

  const entries: RecognitionIndexEntry[] = []
  const owned: Uint8Array[] = []
  const takeOwnership = (buffer: Uint8Array): Uint8Array => {
    // Register IMMEDIATELY, before any later stale check, so a late success
    // after an intervening invalidation is still wiped.
    owned.push(buffer)
    return buffer
  }
  const disposePartial = (): void => {
    for (const entry of entries) {
      for (const row of entry.fingerprint.quantizedSiftDescriptors) row.fill(0)
    }
    entries.length = 0
    for (const buffer of owned) buffer.fill(0)
    owned.length = 0
    fence.disposed = true
  }

  try {
    for (const item of snapshot) {
      fenceOrRefuse()
      const item1 = catalog.items.find((candidate) => candidate.capsuleId === item.capsuleId)
      if (!item1) refuse('invalid')

      // ---- envelope open + strict schema/binding (collage/open.ts pattern) ----
      let opened: Uint8Array
      try {
        opened = takeOwnership(await caps.openEnvelope(
          recipientEnvelopeInfo({
            capsuleId: item.capsuleId,
            senderUserId: item.senderUserId,
            recipientUserId: fence.accountId,
            recipientKeyBundleId: item.recipientKeyBundleId,
          }),
          item.envelopeCiphertext,
        ))
      } catch {
        refuse('invalid')
      }
      fenceOrRefuse()
      let keyset: Uint8Array
      try {
        const fields = readMessage(opened, [1, 2, 3, 4, 5, 6, 7, 8])
        if (fieldNumber(fields, 1) !== 1) refuse('invalid')
        const capsuleIdRaw = fieldBytes(fields, 2)
        const senderUserIdRaw = fieldBytes(fields, 3)
        const recipientUserIdRaw = fieldBytes(fields, 4)
        const senderBundleRaw = fieldBytes(fields, 5)
        const recipientBundleRaw = fieldBytes(fields, 6)
        const statementSha = fieldBytes(fields, 8)
        if (
          capsuleIdRaw.length !== 16 || senderUserIdRaw.length !== 16 || recipientUserIdRaw.length !== 16
          || senderBundleRaw.length !== 16 || recipientBundleRaw.length !== 16 || statementSha.length !== 32
        ) refuse('invalid')
        if (
          uuidText(capsuleIdRaw) !== item.capsuleId
          || uuidText(senderUserIdRaw) !== item.senderUserId
          || uuidText(recipientUserIdRaw) !== fence.accountId
          || uuidText(senderBundleRaw) !== item.senderKeyBundleId
          || uuidText(recipientBundleRaw) !== item.recipientKeyBundleId
          || !equalBytes(statementSha, item.statementSha256)
        ) refuse('invalid')
        const rawKeyset = fieldBytes(fields, 7)
        if (rawKeyset.length === 0) refuse('invalid')
        keyset = takeOwnership(rawKeyset.slice())
      } catch (error) {
        if (error instanceof RecognitionIndexError) throw error
        refuse('invalid')
      }

      // ---- recognition ciphertext: existing download + size/sha256 checks ----
      let ciphertext: Uint8Array
      try {
        ciphertext = takeOwnership(await downloadCiphertext(session, transport, item1, item.recognitionBlobId, signal))
      } catch (error) {
        if (error instanceof SyncError && (error.code === 'CANCELLED' || error.code === 'STALE')) refuse('stale')
        refuse('invalid')
      }
      fenceOrRefuse()

      // ---- exact existing AAD decrypt (RECOGNITION_MANIFEST, ordinal -1) ----
      let manifestPlain: Uint8Array
      try {
        manifestPlain = takeOwnership(await caps.decryptArtifact(
          keyset,
          artifactAad({
            capsuleId: item.capsuleId,
            blobId: item.recognitionBlobId,
            kind: 'RECOGNITION_MANIFEST',
            ordinal: -1,
            senderUserId: item.senderUserId,
            recipientUserId: fence.accountId,
          }),
          ciphertext,
          'RECOGNITION_MANIFEST',
        ))
      } catch {
        refuse('invalid')
      }
      fenceOrRefuse()

      // ---- strict v2 manifest read + accepted format-3 parse ----
      let wireOwned: Uint8Array | null = null
      try {
        const content = readRecognitionManifest(manifestPlain, uuidToBytes(item.capsuleId))
        wireOwned = takeOwnership(content.frontFingerprint)
        let fingerprint: SiftFingerprint
        try {
          fingerprint = parseSiftFingerprint(wireOwned)
        } catch {
          refuse('invalid')
        }
        entries.push({ capsuleId: item.capsuleId, fingerprint })
        wireOwned = null // descriptor rows are independent copies; wipe via owned list at dispose
      } catch (error) {
        wireOwned?.fill(0)
        if (error instanceof RecognitionIndexError) throw error
        refuse('invalid')
      }
    }
    fenceOrRefuse()
    return new RecognitionIndex(entries, owned, fence, { hasMore: catalog.hasMore, nextCursor: catalog.nextCursor })
  } catch (error) {
    disposePartial()
    throw error
  }
}
