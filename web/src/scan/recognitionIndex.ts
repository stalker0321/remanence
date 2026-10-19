import type { MemoryKeys } from '../auth/memoryKeys'
import type { ApiTransport, ByteTransport } from '../auth/http'
import { artifactAad, recipientEnvelopeInfo } from '../protocol/context'
import { bytesToHex } from '../protocol/bytes'
import { uuidToBytes } from '../protocol/uuid'
import type { SessionStore } from '../session/session'
import { SyncError } from '../sync/errors'
import { downloadCiphertext, type IncomingBlob, type IncomingCapsule, type IncomingCatalog } from '../sync/incoming'
import { readRecognitionManifest } from '../collage/readRecognitionManifest'
import { parseSiftFingerprint, type SiftFingerprint } from './siftFingerprint'

/**
 * T328 R2 — owner-fenced TRANSIENT pre-grant recognition index for ONE
 * supplied `fetchIncoming` catalog page (existing maximum 100 items). For
 * each item it performs the accepted envelope open, the strict envelope
 * schema/binding checks (mirrored from `collage/open.ts:188-223` with a
 * SCOPED span reader that makes NO hidden parser copies — the accepted
 * shared `protocol/proto.ts` reader is untouched and unused here), the
 * existing `downloadCiphertext` size/sha256 validation for the
 * RECOGNITION_MANIFEST blob against a SYNCHRONOUSLY CAPTURED item snapshot,
 * the exact `artifactAad` decrypt (kind RECOGNITION_MANIFEST, ordinal -1),
 * the strict v2 manifest reader, and the accepted `parseSiftFingerprint`.
 *
 * Ownership/cleanup (original scope 3): every secret-bearing buffer that
 * this build actually allocates (envelope plaintext, recognition
 * ciphertext, decrypted manifest, canonical/fingerprint wire copies) is
 * registered IMMEDIATELY with the transient scope, wiped per item BEFORE
 * the item completes, and wiped again on ANY invalidation/abort/failure.
 * The successful index owns ONLY parsed descriptor rows (independent
 * copies) plus the page flags — never keysets, plaintexts, ciphertexts or
 * wire buffers. A session subscription and the abort listener wipe rows
 * IMMEDIATELY on lock/sign-out/abort during build AND across the returned
 * index lifetime; watchers are removed at disposal (idempotent). Owner/
 * account/bundle/generation/capability/phase fences are unchanged.
 *
 * Metadata snapshot (original scope 2): page flags and ALL used routing/
 * byte/blob metadata (strings, envelope ciphertext copy, statement digest
 * copy, blob descriptors, and the derived `IncomingCapsule` handed to
 * `downloadCiphertext`) are captured SYNCHRONOUSLY before the first await;
 * the caller's catalog is never reread or mutated. Reads are sequential;
 * ANY integrity/structural error fails the WHOLE build (no partial return,
 * no silent skip). No content/photo download, no grant/admission/phase
 * change, no logging/persistence. The supplied page's hasMore/nextCursor
 * are preserved verbatim; a unique match on this page is NOT a
 * global-uniqueness claim.
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

function wipeEntry(entry: RecognitionIndexEntry): void {
  for (const row of entry.fingerprint.quantizedSiftDescriptors) row.fill(0)
  entry.fingerprint.quantizedSiftDescriptors.length = 0
  entry.fingerprint.keypoints.length = 0
}

export class RecognitionIndex {
  readonly hasMore: boolean
  readonly nextCursor: string | null
  #entries: RecognitionIndexEntry[]
  #fence: OwnerFence
  #emptied = false
  #unsubscribe: () => void
  #onAbort: () => void

  constructor(entries: RecognitionIndexEntry[], fence: OwnerFence, page: { hasMore: boolean; nextCursor: string | null }) {
    this.#entries = entries
    this.#fence = fence
    this.hasMore = page.hasMore
    this.nextCursor = page.nextCursor
    // Immediate cleanup across the LIVE lifetime: lock/sign-out/abort wipes
    // rows now, without waiting for entries()/caller finally.
    this.#unsubscribe = fence.session.subscribe(() => {
      if (!fenceCurrent(fence)) this.#wipe()
    })
    this.#onAbort = () => this.#wipe()
    fence.signal?.addEventListener('abort', this.#onAbort, { once: true })
    // Close the window between the build's final fence and this subscription.
    if (!fenceCurrent(fence)) this.#wipe()
  }

  get disposed(): boolean {
    return this.#fence.disposed || this.#emptied
  }

  /** Refuses (typed stale) once the owner/session/caps/signal context dies. */
  entries(): readonly RecognitionIndexEntry[] {
    if (this.#emptied || !fenceCurrent(this.#fence)) refuse('stale')
    return this.#entries
  }

  /** Idempotent. Removes watchers and wipes every descriptor row. */
  dispose(): void {
    this.#unsubscribe()
    this.#fence.signal?.removeEventListener('abort', this.#onAbort)
    this.#fence.disposed = true
    this.#wipe()
  }

  #wipe(): void {
    if (this.#emptied) return
    this.#emptied = true
    for (const entry of this.#entries) wipeEntry(entry)
    this.#entries = []
  }
}

/** Fully owned, immutable-per-build capture of one catalog item (scope 2). */
interface ItemSnapshot {
  capsuleId: string
  senderUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  /** Owned copy; wiped with the item's temporaries. */
  envelopeCiphertext: Uint8Array
  /** Owned copy; wiped with the item's temporaries. */
  statementSha256: Uint8Array
  recognitionBlobId: string
  /** Derived owner snapshot handed to the accepted download validation. */
  downloadItem: IncomingCapsule
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

interface EnvelopeViews {
  capsuleId: Uint8Array
  senderUserId: Uint8Array
  recipientUserId: Uint8Array
  senderBundleId: Uint8Array
  recipientBundleId: Uint8Array
  keyset: Uint8Array
  statementSha256: Uint8Array
}

/**
 * Scoped bounded envelope reader: records zero-copy SUBARRAY VIEWS of the
 * owned opened plaintext (no hidden slices, no shared-parser copies) so
 * wiping `opened` covers every view on success AND on any later throw.
 * Rejects duplicates/unknown fields/wrong wires exactly like the accepted
 * `collage/open.ts` parseEnvelope contract.
 */
function scanEnvelopeViews(opened: Uint8Array): EnvelopeViews {
  const seen = new Set<number>()
  const spans = new Map<number, { start: number; length: number; wire: 0 | 2; value?: number }>()
  let pos = 0
  const uint32 = (): number => {
    let value = 0
    let factor = 1
    for (let i = 0; i < 5; i++) {
      if (pos >= opened.length) refuse('invalid')
      const piece = opened[pos++]
      if (piece === undefined) refuse('invalid')
      value += (piece & 127) * factor
      if ((piece & 128) === 0) {
        if (value > 0xffffffff) refuse('invalid')
        return value
      }
      factor *= 128
    }
    return refuse('invalid')
  }
  while (pos < opened.length) {
    const tag = uint32()
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (field < 1 || field > 8 || seen.has(field)) refuse('invalid')
    seen.add(field)
    if (wire === 0) {
      const value = uint32()
      spans.set(field, { start: pos, length: 0, wire: 0, value })
    } else if (wire === 2) {
      const length = uint32()
      if (length > opened.length - pos) refuse('invalid')
      spans.set(field, { start: pos, length, wire: 2 })
      pos += length
    } else {
      refuse('invalid')
    }
  }
  const view = (field: number, length: number): Uint8Array => {
    const span = spans.get(field)
    if (!span || span.wire !== 2 || span.length !== length) refuse('invalid')
    return opened.subarray(span.start, span.start + length)
  }
  const version = spans.get(1)
  if (!version || version.wire !== 0 || version.value !== 1) refuse('invalid')
  return {
    capsuleId: view(2, 16),
    senderUserId: view(3, 16),
    recipientUserId: view(4, 16),
    senderBundleId: view(5, 16),
    recipientBundleId: view(6, 16),
    keyset: view(7, spans.get(7)?.length ?? -1),
    statementSha256: view(8, 32),
  }
}

export interface BuildRecognitionIndexDeps {
  readonly session: SessionStore
  readonly keys: MemoryKeys
  readonly transport: ApiTransport & ByteTransport
  readonly catalog: IncomingCatalog
  readonly signal?: AbortSignal
}

/** Transient scope: immediate wipe of EVERY actually allocated secret buffer
 * AND of any already-parsed partial descriptor entries attached to it. */
class TransientScope {
  invalidated = false
  readonly #owned: Uint8Array[] = []
  #entries: RecognitionIndexEntry[] | null = null

  /** Attach the build's partial-entry list so invalidation wipes rows NOW. */
  attachEntries(entries: RecognitionIndexEntry[]): void {
    this.#entries = entries
  }

  /** Success path only: the returned index owns the entries, not the scope. */
  detachEntries(): void {
    this.#entries = null
  }

  take(buffer: Uint8Array): Uint8Array {
    this.#owned.push(buffer)
    if (this.invalidated) buffer.fill(0)
    return buffer
  }

  wipeAll(): void {
    this.invalidated = true
    for (const buffer of this.#owned) buffer.fill(0)
    this.#owned.length = 0
    if (this.#entries !== null) {
      for (const entry of this.#entries) wipeEntry(entry)
      this.#entries.length = 0
    }
  }
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
    if (scope.invalidated) refuse('stale')
    if (!fenceCurrent(fence)) refuse('stale')
  }

  const scope = new TransientScope()

  // Immediate cleanup during BUILD: lock/sign-out/abort wipes everything
  // owned SO FAR at once; late awaited returns are wiped by the take() path.
  const onBuildInvalidate = (): void => {
    if (!fenceCurrent(fence)) scope.wipeAll()
  }
  let unsubscribe: () => void = () => {}
  const onAbort: () => void = () => scope.wipeAll()
  unsubscribe = session.subscribe(onBuildInvalidate)
  signal?.addEventListener('abort', onAbort, { once: true })

  const entries: RecognitionIndexEntry[] = []
  scope.attachEntries(entries)
  const disposePartialEntries = (): void => {
    for (const entry of entries) wipeEntry(entry)
    entries.length = 0
  }

  try {
    // ---- synchronous immutable snapshot of page flags + all used metadata
    // (scope 2): captured BEFORE the first await; the caller's catalog is
    // never reread or mutated afterwards. Every owned copy is registered
    // with the scope the moment it is made, so a mid-snapshot refusal
    // cannot leak.
    const page = { hasMore: catalog.hasMore, nextCursor: catalog.nextCursor }
    const seen = new Set<string>()
    const snapshot: ItemSnapshot[] = []
    for (const item of catalog.items) {
      if (item.recipientUserId !== fence.accountId || item.recipientKeyBundleId !== fence.bundleId) refuse('invalid')
      if (seen.has(item.capsuleId)) refuse('invalid')
      seen.add(item.capsuleId)
      const recognition = item.blobs.filter((blob: IncomingBlob) => blob.kind === 'RECOGNITION_MANIFEST')
      if (recognition.length !== 1 || recognition[0]?.ordinal !== null) refuse('invalid')
      const envelopeCopy = scope.take(item.envelopeCiphertext.slice())
      const statementCopy = scope.take(item.statementSha256.slice())
      const blobsCopy: IncomingBlob[] = item.blobs.map((blob) => ({ ...blob }))
      snapshot.push({
        capsuleId: item.capsuleId,
        senderUserId: item.senderUserId,
        senderKeyBundleId: item.senderKeyBundleId,
        recipientKeyBundleId: item.recipientKeyBundleId,
        envelopeCiphertext: envelopeCopy,
        statementSha256: statementCopy,
        recognitionBlobId: recognition[0]!.blobId,
        downloadItem: {
          capsuleId: item.capsuleId,
          senderUserId: item.senderUserId,
          recipientUserId: item.recipientUserId,
          senderKeyBundleId: item.senderKeyBundleId,
          recipientKeyBundleId: item.recipientKeyBundleId,
          envelopeCiphertext: envelopeCopy,
          statementSha256: statementCopy,
          blobs: blobsCopy,
        },
      })
    }

    for (const item of snapshot) {
      fenceOrRefuse()
      const itemBuffers: Uint8Array[] = []
      const ownItem = (buffer: Uint8Array): Uint8Array => {
        itemBuffers.push(buffer)
        return scope.take(buffer)
      }
      try {
        // ---- envelope open (owned plaintext) + strict schema/binding ----
        let opened: Uint8Array
        try {
          opened = ownItem(await caps.openEnvelope(
            recipientEnvelopeInfo({
              capsuleId: item.capsuleId,
              senderUserId: item.senderUserId,
              recipientUserId: fence.accountId,
              recipientKeyBundleId: item.recipientKeyBundleId,
            }),
            item.envelopeCiphertext,
          ))
        } catch (error) {
          if (error instanceof RecognitionIndexError) throw error
          refuse('invalid')
        }
        fenceOrRefuse()
        let views: EnvelopeViews
        try {
          views = scanEnvelopeViews(opened)
        } catch (error) {
          if (error instanceof RecognitionIndexError) throw error
          refuse('invalid')
        }
        if (
          uuidText(views.capsuleId) !== item.capsuleId
          || uuidText(views.senderUserId) !== item.senderUserId
          || uuidText(views.recipientUserId) !== fence.accountId
          || uuidText(views.senderBundleId) !== item.senderKeyBundleId
          || uuidText(views.recipientBundleId) !== item.recipientKeyBundleId
          || !equalBytes(views.statementSha256, item.statementSha256)
          || views.keyset.length === 0
        ) refuse('invalid')

        // ---- recognition ciphertext: accepted download + hash validation
        // against the CAPTURED snapshot item ----
        let ciphertext: Uint8Array
        try {
          ciphertext = ownItem(await downloadCiphertext(session, transport, item.downloadItem, item.recognitionBlobId, signal))
        } catch (error) {
          if (error instanceof SyncError && (error.code === 'CANCELLED' || error.code === 'STALE')) refuse('stale')
          refuse('invalid')
        }
        fenceOrRefuse()

        // ---- exact existing AAD decrypt (RECOGNITION_MANIFEST, ordinal -1);
        // the keyset is a zero-copy VIEW of the owned opened plaintext. ----
        let manifestPlain: Uint8Array
        try {
          manifestPlain = ownItem(await caps.decryptArtifact(
            views.keyset,
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
        } catch (error) {
          if (error instanceof RecognitionIndexError) throw error
          refuse('invalid')
        }
        fenceOrRefuse()

        // ---- strict v2 manifest read + accepted format-3 parse ----
        let fingerprint: SiftFingerprint
        try {
          const content = readRecognitionManifest(manifestPlain, uuidToBytes(item.capsuleId))
          const wire = ownItem(content.frontFingerprint)
          fingerprint = parseSiftFingerprint(wire)
        } catch (error) {
          if (error instanceof RecognitionIndexError) throw error
          refuse('invalid')
        }
        if (scope.invalidated) refuse('stale')
        entries.push({ capsuleId: item.capsuleId, fingerprint })
      } finally {
        // Per-item temporaries are wiped BEFORE the item completes (scope 3):
        // descriptor rows above are independent copies and stay owned by the
        // scope/entries. Re-fill is idempotent after any invalidation wipe.
        for (const buffer of itemBuffers) buffer.fill(0)
      }
    }
    fenceOrRefuse()

    // Build watchers are no longer needed; the returned index installs its
    // OWN live-lifetime watchers in its constructor. Detach the successful
    // entries BEFORE the residual buffer wipe so only temporaries are
    // cleared here — the index owns the rows from this point on.
    unsubscribe()
    signal?.removeEventListener('abort', onAbort)
    scope.detachEntries()
    scope.wipeAll() // any residual registered snapshot copies are zeroed
    return new RecognitionIndex(entries, fence, page)
  } catch (error) {
    disposePartialEntries()
    scope.wipeAll()
    throw error
  } finally {
    unsubscribe()
    signal?.removeEventListener('abort', onAbort)
  }
}
