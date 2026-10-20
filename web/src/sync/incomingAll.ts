/**
 * T353 — complete bounded incoming collector.
 *
 * The accepted server paginates by an opaque canonical cursor and, importantly,
 * a TERMINAL page (`has_more=false`) can still carry a non-null `next_cursor`
 * (the last item's cursor). The accepted engine requires a COMPLETE catalog with
 * `hasMore=false` AND `nextCursor=null`; this collector is the only adapter that
 * normalizes the terminal page after every server page has been exhausted. It
 * never weakens the engine and never returns a partial page set as complete.
 *
 * Each page is fetched with the unchanged `fetchIncoming` (per-item signed
 * statement/envelope/recipient/hash validation). The collector adds only:
 * - sequential cursor routing through `ApiRequest.query`;
 * - owner/phase/generation/signal checks before and after EVERY page await;
 * - immediate ownership of each returned page (before post-await checks) so a
 *   late page is wiped on abort/stale/refusal;
 * - loop/duplicate/empty-with-more/malformed/budget refusal (no dedupe);
 * - conservative client guardrails chosen by the lead (not protocol changes).
 *
 * The successful aggregate is OWNED by the caller (the App attempt). Call
 * `wipeIncomingCatalog` when the attempt finishes. The engine/index/grant/
 * receive/decrypt paths are unchanged.
 */

import type { ApiTransport } from '../auth/http'
import type { SessionStore } from '../session/session'
import { SyncError } from './errors'
import { fetchIncoming, type IncomingCatalog, type IncomingCapsule } from './incoming'

/** Lead initial CLIENT guardrails (not protocol/product limits). */
export const COMPLETE_INCOMING_GUARDRAILS = Object.freeze({
  pageSize: 25,
  maxRequests: 16,
  maxItems: 200,
  maxMetadataBytes: 4 * 1024 * 1024,
  maxRecognitionCiphertextBytes: 32 * 1024 * 1024,
} as const)

const CURSOR_LENGTH = 34
const CURSOR_BYTES = 25
const CURSOR_VERSION = 1
const CURSOR_RE = /^[A-Za-z0-9_-]{34}$/

function base64UrlDecode(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/**
 * Canonical version-1 incoming cursor: exactly 34 unpadded base64url chars
 * encoding 25 bytes, first byte 1. This is the existing server contract; the
 * cursor is opaque routing, never an auth credential.
 */
export function isCanonicalIncomingCursor(value: unknown): value is string {
  if (typeof value !== 'string' || value.length !== CURSOR_LENGTH || !CURSOR_RE.test(value)) return false
  try {
    const bytes = base64UrlDecode(value)
    return bytes.length === CURSOR_BYTES && bytes[0] === CURSOR_VERSION && base64UrlEncode(bytes) === value
  } catch {
    return false
  }
}

const textEncoder = new TextEncoder()

/**
 * Deterministic metadata accounting (NOT JS heap measurement): decoded envelope
 * bytes + statement hash bytes + UTF-8 routing/blob strings + fixed 8-byte
 * number slots for blob size/ordinal. Item/page caps bound object overhead.
 */
export function incomingMetadataBytes(item: IncomingCapsule): number {
  let total = item.envelopeCiphertext.length + item.statementSha256.length
  const strings = [
    item.capsuleId,
    item.senderUserId,
    item.recipientUserId,
    item.senderKeyBundleId,
    item.recipientKeyBundleId,
  ]
  for (const blob of item.blobs) {
    strings.push(blob.blobId, blob.kind, blob.ciphertextSha256)
    total += 8
    if (blob.ordinal !== null) total += 8
  }
  for (const value of strings) total += textEncoder.encode(value).length
  return total
}

/** Declared recognition ciphertext bytes (checked before any download). */
export function incomingRecognitionCiphertextBytes(item: IncomingCapsule): number {
  let total = 0
  for (const blob of item.blobs) {
    if (blob.kind === 'RECOGNITION_MANIFEST') total += blob.ciphertextSize
  }
  return total
}

/** Wipes the owned metadata buffers of a collected (or partially collected) set. */
export function wipeIncomingCatalog(items: readonly IncomingCapsule[]): void {
  for (const item of items) {
    try {
      item.envelopeCiphertext.fill(0)
    } catch {
      /* defensive: keep wiping the rest */
    }
    try {
      item.statementSha256.fill(0)
    } catch {
      /* defensive: keep wiping the rest */
    }
  }
}

/**
 * Sequentially collect a COMPLETE incoming page set.
 *
 * Returns `{ items, hasMore:false, nextCursor:null }` ONLY after a terminal
 * `has_more=false` page, with server item order preserved. Any loop, duplicate,
 * empty-with-more, malformed cursor/flag, guardrail breach, failed page,
 * cancellation or owner change refuses (typed `SyncError`) and wipes every
 * owned buffer; no partial catalog is ever returned as complete.
 */
export async function fetchCompleteIncoming(
  session: SessionStore,
  transport: ApiTransport,
  signal?: AbortSignal,
): Promise<IncomingCatalog> {
  // Capture owner/phase/generation and the original signal BEFORE the first await.
  const view = session.getSnapshot()
  if (view.phase !== 'keys-unlocked' || view.accountId === null || view.bundleId === null) {
    throw new SyncError('ILLEGAL_PHASE')
  }
  const generation = view.generation
  const accountId = view.accountId
  const bundleId = view.bundleId
  const assertLive = (): void => {
    if (signal?.aborted === true) throw new SyncError('CANCELLED')
    const now = session.getSnapshot()
    if (
      now.phase !== 'keys-unlocked' ||
      now.accountId !== accountId ||
      now.bundleId !== bundleId ||
      now.generation !== generation
    ) {
      throw new SyncError('STALE')
    }
  }

  const owned: IncomingCapsule[] = []
  const seen = new Set<string>()
  // Attempt-wide requested/intermediate cursors. Refuse ANY replay before
  // issuing it again; terminal echoes are not requested and stay allowed.
  const requestedCursors = new Set<string>()
  let requests = 0
  let cursor: string | null = null
  let metadataBytes = 0
  let recognitionBytes = 0
  try {
    for (;;) {
      assertLive()
      if (requests >= COMPLETE_INCOMING_GUARDRAILS.maxRequests) throw new SyncError('INCOMPLETE')
      requests += 1
      let page: IncomingCatalog
      try {
        page = await fetchIncoming(session, transport, signal, {
          cursor,
          limit: COMPLETE_INCOMING_GUARDRAILS.pageSize,
        })
      } catch (error) {
        if (signal?.aborted === true) throw new SyncError('CANCELLED')
        // A rejected await is still an owner/phase boundary: recheck the
        // captured context before mapping the dependency error.
        assertLive()
        if (error instanceof SyncError) throw error
        throw new SyncError('SYNC_REJECTED')
      }
      // Own the late page IMMEDIATELY, before any post-await owner check.
      for (const item of page.items) owned.push(item)
      assertLive()

      if (typeof page.hasMore !== 'boolean') throw new SyncError('INCOMPLETE')
      if (page.nextCursor !== null && !isCanonicalIncomingCursor(page.nextCursor)) {
        throw new SyncError('INCOMPLETE')
      }
      if (page.hasMore === true) {
        if (page.items.length === 0) throw new SyncError('INCOMPLETE')
        if (page.nextCursor === null) throw new SyncError('INCOMPLETE')
        if (requestedCursors.has(page.nextCursor)) throw new SyncError('INCOMPLETE')
        requestedCursors.add(page.nextCursor)
      }

      for (const item of page.items) {
        if (typeof item.capsuleId !== 'string' || item.capsuleId.length === 0) throw new SyncError('INCOMPLETE')
        if (seen.has(item.capsuleId)) throw new SyncError('INCOMPLETE')
        seen.add(item.capsuleId)
        if (seen.size > COMPLETE_INCOMING_GUARDRAILS.maxItems) throw new SyncError('INCOMPLETE')
        metadataBytes += incomingMetadataBytes(item)
        if (metadataBytes > COMPLETE_INCOMING_GUARDRAILS.maxMetadataBytes) throw new SyncError('INCOMPLETE')
        recognitionBytes += incomingRecognitionCiphertextBytes(item)
        if (recognitionBytes > COMPLETE_INCOMING_GUARDRAILS.maxRecognitionCiphertextBytes) {
          throw new SyncError('INCOMPLETE')
        }
      }

      if (page.hasMore === false) {
        // Terminal semantics: a non-null terminal cursor is valid and ignored;
        // only an exhausted set is presented as complete.
        return { items: owned, hasMore: false, nextCursor: null }
      }
      cursor = page.nextCursor
    }
  } catch (error) {
    wipeIncomingCatalog(owned)
    if (error instanceof SyncError) throw error
    if (signal?.aborted === true) throw new SyncError('CANCELLED')
    throw new SyncError('SYNC_REJECTED')
  }
}
