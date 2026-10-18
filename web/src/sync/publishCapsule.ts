import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { AuthError } from '../auth/errors'
import type { ApiResult, ApiTransport, UploadTransport } from '../auth/http'
import type { SealedCapsule, SealedCapsuleBlob } from '../create/sealCapsule'
import { bytesToHex, utf8 } from '../protocol/bytes'
import { limits } from '../protocol/limits'
import { isCanonicalUuid, nameUuidFromBytes } from '../protocol/uuid'
import type { SessionStore } from '../session/session'

/**
 * T304 — existing-user one-attempt three-step ciphertext publisher.
 *
 * Consumes the accepted `sealCapsule` output (already-encrypted, signed public
 * metadata) and drives the EXACT current backend sequence over the accepted
 * `fetchTransport`: draft POST (`Idempotency-Key`) -> per-blob raw PUT (skipping
 * server `STORED` receipts, deterministic per-blob nameUUID) -> finalize POST.
 *
 * It is a transport/session adapter, NOT a new cryptographic acceptance boundary:
 * it never reads/verifies the publish statement binding nor the signature (the
 * backend/receiver own that), never accepts/returns plaintext or private keys,
 * and does not implement a new statement reader. Signature/binding authenticity
 * and backend admission remain the server's job.
 *
 * Owner/session contract: an ACTIVE keys-ready Create visit (not signed-out /
 * authenticated-only) whose account/bundle equal the sealed sender. The captured
 * generation/account/bundle and the readable token are re-checked before and
 * after every await and before returning; a stale/locked/relogged/aborted visit
 * fails closed with no further request. A local abort never claims to roll back a
 * request the server already accepted.
 *
 * Known limitation: after a successful finalize whose response was lost,
 * restarting this one-attempt adapter from the draft raises
 * `CAPSULE_STATE_INVALID` because the server refuses a READY draft replay. That is
 * NEVER success and is not auto-retried; durable phase-aware retry is later outbox
 * work.
 */

export type PublishStage = 'preflight' | 'draft' | 'upload' | 'finalize'

/** Bounded, redacted publisher failure. Never carries a response body or token. */
export class PublishCapsuleError extends Error {
  constructor(
    readonly stage: PublishStage,
    readonly category: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(code ?? category)
    this.name = 'PublishCapsuleError'
  }
}

export interface PublishCapsuleInput {
  capsule: SealedCapsule
  session: SessionStore
  /** Explicit caller-stable canonical draft UUID; never generated here. */
  idempotencyKey: string
  transport: ApiTransport & UploadTransport
  signal?: AbortSignal
}

export interface PublishedCapsule {
  capsuleId: string
  readyAt: string
  locator: { code: string; hint: string } | null
}

const DRAFT_PATH = '/v1/capsules'
const PHOTO = 'PHOTO'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
/** Same typed-UTC syntax the accepted receiver uses for `ready_at`. */
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/
/** Current `pending/locator.py`: 5 chars of this Crockford base32 alphabet. */
const LOCATOR_CODE = /^[0-9A-HJKMNP-TV-Z]{5}$/
const HINT_MAX_UTF8 = 254
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/
const PROBLEM_CODE = /^[A-Z][A-Z0-9_]{0,63}$/
const STATEMENT_MAX = 4096
const SIGNATURE_BYTES = 69
const SHA256_BYTES = 32
const ENVELOPE_MAX = 16_384
const TOTAL_MAX = 44_040_192

const KIND_CAP: Record<SealedCapsuleBlob['kind'], number> = {
  RECOGNITION_MANIFEST: limits.recognitionCiphertext,
  CONTENT_MANIFEST: limits.contentCiphertext,
  PHOTO: limits.photoCiphertext,
}

const CATEGORY_BY_CODE: Record<string, string> = {
  AUTH_INVALID: 'auth-invalid',
  AUTH_EXPIRED: 'auth-invalid',
  SESSION_REPLAYED: 'auth-invalid',
  TERMS_REQUIRED: 'terms-required',
  RATE_LIMITED: 'rate-limited',
  CLIENT_UPGRADE_REQUIRED: 'client-upgrade-required',
  IDEMPOTENCY_CONFLICT: 'idempotency-conflict',
  CAPSULE_NOT_FOUND: 'capsule-not-found',
  CAPSULE_STATE_INVALID: 'capsule-state-invalid',
  DRAFT_EXPIRED: 'draft-expired',
  RECIPIENT_KEY_STALE: 'recipient-key-stale',
  KEY_BUNDLE_NOT_FOUND: 'key-bundle',
  KEY_BUNDLE_INVALID: 'key-bundle',
  KEY_BUNDLE_REVOKED: 'key-bundle',
  STATEMENT_INVALID: 'statement-invalid',
  SIGNATURE_INVALID: 'signature-invalid',
  ENVELOPE_INVALID: 'envelope-invalid',
  BLOB_NOT_DECLARED: 'blob',
  BLOB_SIZE_INVALID: 'blob',
  BLOB_HASH_MISMATCH: 'blob',
  BLOB_CONFLICT: 'blob',
  FINALIZE_CONFLICT: 'finalize-conflict',
  INTERNAL_ERROR: 'internal',
  INTERNAL_UNAVAILABLE: 'internal-unavailable',
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const own = Object.keys(value)
  return own.length === keys.length && keys.every((key) => Object.hasOwn(value, key))
}

function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource).then((digest) => new Uint8Array(digest))
}

function problemCode(body: unknown): string | null {
  const root = record(body)
  return root && typeof root.code === 'string' && PROBLEM_CODE.test(root.code) ? root.code : null
}

function categoryFor(code: string | null): string {
  return code !== null && Object.hasOwn(CATEGORY_BY_CODE, code) ? (CATEGORY_BY_CODE[code] as string) : 'http'
}

/** Typed UTC syntax AND a finite `Date.parse` — no freshness/clock-window policy. */
function isUtcTimestamp(value: unknown): value is string {
  return typeof value === 'string' && UTC_TIMESTAMP.test(value) && Number.isFinite(Date.parse(value))
}

interface OwnedBlob {
  blobId: string
  kind: SealedCapsuleBlob['kind']
  ordinal: number
  ciphertext: Uint8Array
  ciphertextSize: number
  ciphertextSha256: Uint8Array
}

interface OwnedCapsule {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  statement: Uint8Array
  signature: Uint8Array
  statementSha256: Uint8Array
  envelopeCiphertext: Uint8Array
  blobs: OwnedBlob[]
}

/** Own every mutable byte/metadata field before the first await. */
function ownCapsule(capsule: SealedCapsule): OwnedCapsule {
  return {
    capsuleId: capsule.capsuleId,
    senderUserId: capsule.senderUserId,
    recipientUserId: capsule.recipientUserId,
    senderKeyBundleId: capsule.senderKeyBundleId,
    recipientKeyBundleId: capsule.recipientKeyBundleId,
    statement: capsule.statement.slice(),
    signature: capsule.signature.slice(),
    statementSha256: capsule.statementSha256.slice(),
    envelopeCiphertext: capsule.envelopeCiphertext.slice(),
    blobs: capsule.blobs.map((blob) => ({
      blobId: blob.blobId,
      kind: blob.kind,
      ordinal: blob.ordinal,
      ciphertext: blob.ciphertext.slice(),
      ciphertextSize: blob.ciphertextSize,
      ciphertextSha256: blob.ciphertextSha256.slice(),
    })),
  }
}

function refuse(stage: PublishStage, category: string, status?: number, code?: string): PublishCapsuleError {
  return new PublishCapsuleError(stage, category, status, code)
}

/** Map a known transport `AuthError` (or a publisher error) to a bounded error. */
function transportError(stage: PublishStage, error: unknown): PublishCapsuleError {
  if (error instanceof PublishCapsuleError) return error
  if (error instanceof AuthError) {
    const category = error.code === 'CANCELLED' ? 'cancelled' : error.code === 'STALE' ? 'stale' : 'transport'
    return refuse(stage, category)
  }
  throw error
}

export async function publishCapsule(input: PublishCapsuleInput): Promise<PublishedCapsule> {
  const { capsule, session, idempotencyKey, transport, signal } = input

  // ---- preflight: session/owner/bundle + shape/bounds (synchronous) ----
  const view = session.getSnapshot()
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) {
    throw refuse('preflight', 'phase')
  }
  if (view.accountId !== capsule.senderUserId) throw refuse('preflight', 'owner')
  if (view.bundleId === null || view.bundleId !== capsule.senderKeyBundleId) throw refuse('preflight', 'bundle')
  const generation = view.generation
  const accountId = view.accountId
  const bundleId = view.bundleId
  if (!isCanonicalUuid(idempotencyKey)) throw refuse('preflight', 'contract')

  const owned = ownCapsule(capsule)
  for (const value of [owned.capsuleId, owned.senderUserId, owned.recipientUserId, owned.senderKeyBundleId, owned.recipientKeyBundleId]) {
    if (!UUID.test(value)) throw refuse('preflight', 'contract')
  }
  if (owned.statement.length < 1 || owned.statement.length > STATEMENT_MAX) throw refuse('preflight', 'contract')
  if (owned.signature.length !== SIGNATURE_BYTES) throw refuse('preflight', 'contract')
  if (owned.statementSha256.length !== SHA256_BYTES) throw refuse('preflight', 'contract')
  if (owned.envelopeCiphertext.length < 1 || owned.envelopeCiphertext.length > ENVELOPE_MAX) throw refuse('preflight', 'contract')
  if (!Array.isArray(owned.blobs) || owned.blobs.length < 5 || owned.blobs.length > 7) throw refuse('preflight', 'contract')
  const recognition = owned.blobs.filter((blob) => blob.kind === 'RECOGNITION_MANIFEST')
  const content = owned.blobs.filter((blob) => blob.kind === 'CONTENT_MANIFEST')
  const photos = owned.blobs.filter((blob) => blob.kind === 'PHOTO')
  if (recognition.length !== 1 || content.length !== 1 || photos.length < 3 || photos.length > 5) throw refuse('preflight', 'contract')
  const ids = new Set<string>()
  let total = 0
  for (let index = 0; index < owned.blobs.length; index++) {
    const blob = owned.blobs[index]!
    if (!UUID.test(blob.blobId) || ids.has(blob.blobId)) throw refuse('preflight', 'contract')
    ids.add(blob.blobId)
    // Unknown kinds are refused before any HTTP write (never left to the backend).
    if (!Object.hasOwn(KIND_CAP, blob.kind)) throw refuse('preflight', 'contract')
    if (!Number.isInteger(blob.ciphertextSize) || blob.ciphertextSize < 1 || blob.ciphertextSize > KIND_CAP[blob.kind]) throw refuse('preflight', 'contract')
    if (!(blob.ciphertext instanceof Uint8Array) || blob.ciphertext.length !== blob.ciphertextSize) throw refuse('preflight', 'contract')
    if (!(blob.ciphertextSha256 instanceof Uint8Array) || blob.ciphertextSha256.length !== SHA256_BYTES) throw refuse('preflight', 'contract')
    total += blob.ciphertextSize
  }
  if (total > TOTAL_MAX) throw refuse('preflight', 'contract')
  const photoOrdinals = photos.map((blob) => blob.ordinal)
  if (photoOrdinals.some((ordinal, index) => ordinal !== index)) throw refuse('preflight', 'contract')
  if (recognition[0]!.ordinal !== -1 || content[0]!.ordinal !== -1) throw refuse('preflight', 'contract')

  // ---- fence + token for the captured visit ----
  const guard = (stage: PublishStage): void => {
    if (signal?.aborted) throw refuse(stage, 'cancelled')
    const next = session.getSnapshot()
    if (
      next.generation !== generation
      || next.phase === 'signed-out'
      || next.phase === 'authenticated'
      || next.accountId !== accountId
      || next.bundleId !== bundleId
    ) {
      throw refuse(stage, 'stale')
    }
  }
  const token = (stage: PublishStage): string => {
    guard(stage)
    const secrets = session.secretsFor(generation)
    if (!secrets || session.getSnapshot().accountId !== accountId) throw refuse(stage, 'stale')
    return secrets.accessToken
  }
  const fenced = async <T>(stage: PublishStage, work: () => Promise<T>): Promise<T> => {
    guard(stage)
    const value = await work()
    guard(stage)
    return value
  }

  // ---- before the FIRST HTTP write: actual lengths + digests match declarations ----
  for (const blob of owned.blobs) {
    const digest = await fenced('preflight', () => sha256(blob.ciphertext))
    if (bytesToHex(digest) !== bytesToHex(blob.ciphertextSha256)) throw refuse('preflight', 'contract')
  }
  const rawStatementSha256 = await fenced('preflight', () => sha256(owned.statement))
  if (bytesToHex(rawStatementSha256) !== bytesToHex(owned.statementSha256)) throw refuse('preflight', 'contract')
  const envelopeSha256 = await fenced('preflight', () => sha256(owned.envelopeCiphertext))

  // ---- step 1: draft POST with the explicit idempotency key ----
  const draftBody = {
    capsule_id: owned.capsuleId,
    recipient_user_id: owned.recipientUserId,
    sender_key_bundle_id: owned.senderKeyBundleId,
    recipient_key_bundle_id: owned.recipientKeyBundleId,
    protocol_version: 1,
    blobs: owned.blobs.map((blob) => ({
      blob_id: blob.blobId,
      kind: blob.kind,
      ordinal: blob.kind === PHOTO ? blob.ordinal : null,
      ciphertext_size: blob.ciphertextSize,
      ciphertext_sha256: b64(blob.ciphertextSha256),
    })),
  }
  let draft: ApiResult
  try {
    draft = await fenced('draft', () => transport.request({ path: DRAFT_PATH, method: 'POST', token: token('draft'), idempotencyKey, body: draftBody, signal }))
  } catch (error) {
    throw transportError('draft', error)
  }
  if (draft.status !== 200 && draft.status !== 201) throw refuse('draft', categoryFor(problemCode(draft.body)), draft.status, problemCode(draft.body) ?? undefined)
  const stored = readDraftReceipt(draft.body, owned)

  // ---- step 2: PUT only DECLARED blobs (server STORED are skipped) ----
  for (const blob of owned.blobs) {
    if (stored.has(blob.blobId)) continue
    const perBlobKey = nameUuidFromBytes(utf8(`postmark/blob-upload/v1/${owned.capsuleId}/${blob.blobId}`))
    let upload: ApiResult
    try {
      upload = await fenced('upload', () => transport.uploadBytes({
        path: `/v1/capsules/${owned.capsuleId}/blobs/${blob.blobId}`,
        token: token('upload'),
        idempotencyKey: perBlobKey,
        ciphertextSha256: blob.ciphertextSha256,
        body: blob.ciphertext,
        signal,
      }))
    } catch (error) {
      throw transportError('upload', error)
    }
    if (upload.status !== 204) throw refuse('upload', categoryFor(problemCode(upload.body)), upload.status, problemCode(upload.body) ?? undefined)
  }

  // ---- step 3: finalize POST with the exact signed statement + envelope ----
  const finalizeBody = {
    signed_publish_statement: {
      statement: b64(owned.statement),
      signature: b64(owned.signature),
      sender_key_bundle_id: owned.senderKeyBundleId,
    },
    recipient_envelope: {
      recipient_key_bundle_id: owned.recipientKeyBundleId,
      ciphertext: b64(owned.envelopeCiphertext),
      ciphertext_size: owned.envelopeCiphertext.length,
      ciphertext_sha256: b64(envelopeSha256),
    },
  }
  let finalize: ApiResult
  try {
    finalize = await fenced('finalize', () => transport.request({ path: `/v1/capsules/${owned.capsuleId}/finalize`, method: 'POST', token: token('finalize'), body: finalizeBody, signal }))
  } catch (error) {
    throw transportError('finalize', error)
  }
  if (finalize.status !== 200 && finalize.status !== 201) throw refuse('finalize', categoryFor(problemCode(finalize.body)), finalize.status, problemCode(finalize.body) ?? undefined)
  const published = readFinalizeReceipt(finalize.body, owned)
  guard('finalize')
  return published
}

/** Strict current `CapsuleDraftResponse`: exact IDs/order/states, no skip. */
function readDraftReceipt(body: unknown, owned: OwnedCapsule): Set<string> {
  const root = record(body)
  if (!root || !exactKeys(root, ['capsule_id', 'state', 'draft_expires_at', 'blobs'])) throw refuse('draft', 'malformed')
  if (root.capsule_id !== owned.capsuleId || root.state !== 'DRAFT') throw refuse('draft', 'malformed')
  if (!isUtcTimestamp(root.draft_expires_at)) throw refuse('draft', 'malformed')
  const blobs = root.blobs
  if (!Array.isArray(blobs) || blobs.length !== owned.blobs.length) throw refuse('draft', 'malformed')
  const stored = new Set<string>()
  for (let index = 0; index < owned.blobs.length; index++) {
    const receipt = record(blobs[index])
    if (!receipt || !exactKeys(receipt, ['blob_id', 'state'])) throw refuse('draft', 'malformed')
    if (receipt.blob_id !== owned.blobs[index]!.blobId) throw refuse('draft', 'malformed')
    if (receipt.state === 'STORED') stored.add(owned.blobs[index]!.blobId)
    else if (receipt.state !== 'DECLARED') throw refuse('draft', 'malformed')
  }
  return stored
}

/** Strict current `CapsuleFinalizeResponse` + optional negotiated locator pair. */
function readFinalizeReceipt(body: unknown, owned: OwnedCapsule): PublishedCapsule {
  const root = record(body)
  if (!root) throw refuse('finalize', 'malformed')
  const keys = Object.keys(root)
  if (keys.length !== 3 && keys.length !== 5) throw refuse('finalize', 'malformed')
  if (root.capsule_id !== owned.capsuleId || root.state !== 'READY') throw refuse('finalize', 'malformed')
  if (!isUtcTimestamp(root.ready_at)) throw refuse('finalize', 'malformed')
  const hasCode = Object.hasOwn(root, 'locator_code')
  const hasHint = Object.hasOwn(root, 'locator_hint')
  if (hasCode !== hasHint) throw refuse('finalize', 'malformed')
  if (!hasCode) {
    if (!exactKeys(root, ['capsule_id', 'state', 'ready_at'])) throw refuse('finalize', 'malformed')
    return { capsuleId: owned.capsuleId, readyAt: root.ready_at, locator: null }
  }
  if (!exactKeys(root, ['capsule_id', 'state', 'ready_at', 'locator_code', 'locator_hint'])) throw refuse('finalize', 'malformed')
  const code = root.locator_code
  const hint = root.locator_hint
  if (typeof code !== 'string' || !LOCATOR_CODE.test(code)) throw refuse('finalize', 'malformed')
  if (typeof hint !== 'string' || hint.length === 0 || utf8(hint).length > HINT_MAX_UTF8 || CONTROL.test(hint)) throw refuse('finalize', 'malformed')
  return { capsuleId: owned.capsuleId, readyAt: root.ready_at, locator: { code, hint } }
}
