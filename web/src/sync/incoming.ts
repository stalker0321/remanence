import { unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import type { ByteTransport } from '../auth/http'
import type { ApiTransport } from '../auth/http'
import { verifyPublishStatement } from '../crypto/publish'
import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { limits } from '../protocol/limits'
import { isCanonicalUuid } from '../protocol/uuid'
import { SessionError, type SessionStore } from '../session/session'
import { SyncError } from './errors'

export interface IncomingBlob {
  blobId: string
  kind: 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO'
  ordinal: number | null
  ciphertextSize: number
  ciphertextSha256: string
}

export interface IncomingCapsule {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  envelopeCiphertext: Uint8Array
  statementSha256: Uint8Array
  blobs: IncomingBlob[]
}

export interface IncomingCatalog {
  items: IncomingCapsule[]
  hasMore: boolean
  nextCursor: string | null
}

const READY_AT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/
const KIND_CAP = {
  RECOGNITION_MANIFEST: limits.recognitionCiphertext,
  CONTENT_MANIFEST: limits.contentCiphertext,
  PHOTO: limits.photoCiphertext,
} as const

/**
 * One page of owner-scoped ciphertext. Verifies the publish signature and
 * the declared hashes. Does not decrypt and does not move the session phase.
 */
export async function fetchIncoming(
  session: SessionStore,
  transport: ApiTransport,
  signal?: AbortSignal,
): Promise<IncomingCatalog> {
  const view = session.getSnapshot()
  if (view.phase === 'signed-out' || view.accountId === null || view.bundleId === null) {
    throw new SyncError('ILLEGAL_PHASE')
  }
  const generation = view.generation
  const accountId = view.accountId
  const bundleId = view.bundleId
  const token = () => {
    if (signal?.aborted) throw new SyncError('CANCELLED')
    try {
      session.bind(generation)()
    } catch (error) {
      if (error instanceof SessionError) throw new SyncError('STALE')
      throw error
    }
    const secrets = session.secretsFor(generation)
    if (!secrets || session.getSnapshot().accountId !== accountId) throw new SyncError('STALE')
    return secrets.accessToken
  }
  const page = await transport.request({
    path: '/v1/capsules/incoming',
    method: 'GET',
    token: token(),
    signal,
  })
  if (page.status !== 200) throw new SyncError('SYNC_REJECTED')
  const parsed = readPage(page.body, accountId, bundleId)
  const signingKeys = new Map<string, Uint8Array>()
  for (const item of parsed.items) {
    let signing = signingKeys.get(item.senderKeyBundleId)
    if (!signing) {
      signing = await loadSigningKey(transport, token(), item.senderKeyBundleId, item.senderUserId, signal)
      signingKeys.set(item.senderKeyBundleId, signing)
    }
    await verifyItem(item, signing)
  }
  token()
  return {
    items: parsed.items.map(publishItem),
    hasMore: parsed.hasMore,
    nextCursor: parsed.nextCursor,
  }
}

export async function downloadCiphertext(
  session: SessionStore,
  transport: ByteTransport,
  item: IncomingCapsule,
  blobId: string,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const view = session.getSnapshot()
  if (view.accountId === null || view.accountId !== item.recipientUserId || view.phase === 'signed-out') {
    throw new SyncError('ILLEGAL_PHASE')
  }
  const blob = item.blobs.find((candidate) => candidate.blobId === blobId)
  if (!blob) throw new SyncError('SYNC_REJECTED')
  const generation = view.generation
  let accessToken: string
  try {
    session.bind(generation)()
    const secrets = session.secretsFor(generation)
    if (!secrets) throw new SyncError('STALE')
    accessToken = secrets.accessToken
  } catch (error) {
    if (error instanceof SessionError) throw new SyncError('STALE')
    throw error
  }
  const response = await transport.requestBytes({
    path: `/v1/capsules/${item.capsuleId}/blobs/${blob.blobId}`,
    token: accessToken,
    signal,
    maxBytes: blob.ciphertextSize,
  })
  try {
    session.bind(generation)()
  } catch (error) {
    if (error instanceof SessionError) throw new SyncError('STALE')
    throw error
  }
  if (response.status !== 200 || response.body.length !== blob.ciphertextSize) throw new SyncError('SYNC_REJECTED')
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(response.body)))
  if (!same(digest, decode(blob.ciphertextSha256, 32))) throw new SyncError('HASH_MISMATCH')
  return response.body
}

async function loadSigningKey(
  transport: ApiTransport,
  token: string,
  bundleId: string,
  senderUserId: string,
  signal: AbortSignal | undefined,
): Promise<Uint8Array> {
  const response = await transport.request({
    path: `/v1/directory/key-bundles/${bundleId}`,
    method: 'GET',
    token,
    signal,
  })
  const root = record(response.status === 200 ? response.body : null)
  if (
    !root
    || root.key_bundle_id !== bundleId
    || root.user_id !== senderUserId
    || root.protocol_version !== 1
    || root.status !== 'ACTIVE'
    || typeof root.signing_public_keyset !== 'string'
  ) {
    throw new SyncError('SYNC_REJECTED')
  }
  return decode(root.signing_public_keyset, 16, 8192)
}

async function verifyItem(item: DraftItem, signingKeyset: Uint8Array): Promise<void> {
  const statement = decode(item.statement, 1, 16384)
  const statementHash = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))
  if (!same(statementHash, decode(item.statementSha256, 32))) throw new SyncError('HASH_MISMATCH')
  const signature = decode(item.signature, 69)
  try {
    await verifyPublishStatement(signingKeyset, statement, signature)
  } catch {
    throw new SyncError('SYNC_REJECTED')
  }
  bindStatement(statement, item)
  const envelope = decode(item.envelope, 1, limits.recipientEnvelopeCiphertext)
  if (envelope.length !== item.envelopeSize) throw new SyncError('SYNC_REJECTED')
  const envelopeHash = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(envelope)))
  if (!same(envelopeHash, decode(item.envelopeSha256, 32))) throw new SyncError('HASH_MISMATCH')
}

interface DraftItem {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  senderKeyBundleId: string
  recipientKeyBundleId: string
  statement: string
  statementSha256: string
  signature: string
  envelope: string
  envelopeSize: number
  envelopeSha256: string
  blobs: IncomingBlob[]
}

function publishItem(item: DraftItem): IncomingCapsule {
  return {
    capsuleId: item.capsuleId,
    senderUserId: item.senderUserId,
    recipientUserId: item.recipientUserId,
    senderKeyBundleId: item.senderKeyBundleId,
    recipientKeyBundleId: item.recipientKeyBundleId,
    envelopeCiphertext: decode(item.envelope, 1, limits.recipientEnvelopeCiphertext),
    statementSha256: decode(item.statementSha256, 32),
    blobs: item.blobs,
  }
}

function readPage(body: unknown, accountId: string, bundleId: string): { items: DraftItem[]; hasMore: boolean; nextCursor: string | null } {
  const root = record(body)
  if (!root || !Array.isArray(root.items) || root.items.length > 100 || typeof root.has_more !== 'boolean') {
    throw new SyncError('SYNC_REJECTED')
  }
  const nextCursor = root.next_cursor
  if (root.has_more) {
    if (typeof nextCursor !== 'string' || nextCursor.length < 1) throw new SyncError('SYNC_REJECTED')
  } else if (!(nextCursor === null || typeof nextCursor === 'string')) {
    throw new SyncError('SYNC_REJECTED')
  }
  return {
    items: root.items.map((item) => readItem(item, accountId, bundleId)),
    hasMore: root.has_more,
    nextCursor: typeof nextCursor === 'string' ? nextCursor : null,
  }
}

function readItem(value: unknown, accountId: string, bundleId: string): DraftItem {
  const root = record(value)
  const statement = record(root?.signed_publish_statement)
  const envelope = record(root?.recipient_envelope)
  if (!root || !statement || !envelope || !Array.isArray(root.blobs)) throw new SyncError('SYNC_REJECTED')
  const capsuleId = uuidField(root.capsule_id)
  const senderUserId = uuidField(root.sender_user_id)
  const recipientUserId = uuidField(root.recipient_user_id)
  const senderKeyBundleId = uuidField(root.sender_key_bundle_id)
  const recipientKeyBundleId = uuidField(root.recipient_key_bundle_id)
  if (recipientUserId !== accountId || recipientKeyBundleId !== bundleId) throw new SyncError('SYNC_REJECTED')
  if (envelope.recipient_key_bundle_id !== recipientKeyBundleId) throw new SyncError('SYNC_REJECTED')
  if (root.protocol_version !== 1 || typeof root.ready_at !== 'string' || !READY_AT.test(root.ready_at)) {
    throw new SyncError('SYNC_REJECTED')
  }
  if (typeof statement.statement !== 'string' || typeof statement.statement_sha256 !== 'string' || typeof statement.signature !== 'string') {
    throw new SyncError('SYNC_REJECTED')
  }
  if (typeof envelope.ciphertext !== 'string' || typeof envelope.ciphertext_sha256 !== 'string' || envelope.ciphertext_size !== decode(envelope.ciphertext, 1, limits.recipientEnvelopeCiphertext).length) {
    throw new SyncError('SYNC_REJECTED')
  }
  return {
    capsuleId,
    senderUserId,
    recipientUserId,
    senderKeyBundleId,
    recipientKeyBundleId,
    statement: statement.statement,
    statementSha256: statement.statement_sha256,
    signature: statement.signature,
    envelope: envelope.ciphertext,
    envelopeSize: envelope.ciphertext_size,
    envelopeSha256: envelope.ciphertext_sha256,
    blobs: readBlobs(root.blobs),
  }
}

function readBlobs(value: unknown[]): IncomingBlob[] {
  const blobs = value.map(readBlob)
  const recognition = blobs.filter((blob) => blob.kind === 'RECOGNITION_MANIFEST')
  const content = blobs.filter((blob) => blob.kind === 'CONTENT_MANIFEST')
  const photos = blobs.filter((blob) => blob.kind === 'PHOTO')
  if (recognition.length !== 1 || content.length !== 1 || photos.length < 3 || photos.length > 5) {
    throw new SyncError('SYNC_REJECTED')
  }
  if (recognition[0]?.ordinal !== null || content[0]?.ordinal !== null) throw new SyncError('SYNC_REJECTED')
  const ordinals = photos.map((blob) => blob.ordinal)
  for (let ordinal = 0; ordinal < photos.length; ordinal++) {
    if (!ordinals.includes(ordinal)) throw new SyncError('SYNC_REJECTED')
  }
  return blobs
}

function readBlob(value: unknown): IncomingBlob {
  const root = record(value)
  if (!root) throw new SyncError('SYNC_REJECTED')
  const kind = root.kind
  if (kind !== 'RECOGNITION_MANIFEST' && kind !== 'CONTENT_MANIFEST' && kind !== 'PHOTO') {
    throw new SyncError('SYNC_REJECTED')
  }
  const ordinal = root.ordinal
  if (kind === 'PHOTO') {
    if (typeof ordinal !== 'number' || !Number.isInteger(ordinal) || ordinal < 0 || ordinal > 4) throw new SyncError('SYNC_REJECTED')
  } else if (ordinal !== null) {
    throw new SyncError('SYNC_REJECTED')
  }
  if (typeof root.ciphertext_size !== 'number' || !Number.isInteger(root.ciphertext_size) || root.ciphertext_size < 1 || root.ciphertext_size > KIND_CAP[kind]) {
    throw new SyncError('OVERSIZED')
  }
  if (typeof root.ciphertext_sha256 !== 'string') throw new SyncError('SYNC_REJECTED')
  decode(root.ciphertext_sha256, 32)
  return {
    blobId: uuidField(root.blob_id),
    kind,
    ordinal: kind === 'PHOTO' ? ordinal : null,
    ciphertextSize: root.ciphertext_size,
    ciphertextSha256: root.ciphertext_sha256,
  }
}

function bindStatement(statement: Uint8Array, item: DraftItem): void {
  // T283: root field 7 (`created_at_epoch_seconds`, proto int64) is read with the
  // scoped signed64 reader below. An omitted field is the proto3 default 0. The
  // parsed value is intentionally unused and never exposed via `IncomingCapsule`;
  // it is NOT a client clock/date policy.
  const fields = walk(statement, [1, 2, 3, 4, 5, 6, 7, 8], 8, 7)
  if (fields.numbers.get(1) !== 1) throw new SyncError('SYNC_REJECTED')
  if (uuidText(fields.bytes.get(2)) !== item.capsuleId) throw new SyncError('SYNC_REJECTED')
  if (uuidText(fields.bytes.get(3)) !== item.senderUserId) throw new SyncError('SYNC_REJECTED')
  if (uuidText(fields.bytes.get(4)) !== item.recipientUserId) throw new SyncError('SYNC_REJECTED')
  if (uuidText(fields.bytes.get(5)) !== item.senderKeyBundleId) throw new SyncError('SYNC_REJECTED')
  if (uuidText(fields.bytes.get(6)) !== item.recipientKeyBundleId) throw new SyncError('SYNC_REJECTED')
  if (fields.repeated.length !== item.blobs.length) throw new SyncError('SYNC_REJECTED')
  const bindings = fields.repeated.map(readBinding)
  for (const blob of item.blobs) {
    const binding = bindings.find((candidate) => candidate.blobId === blob.blobId)
    if (
      !binding
      || binding.kind !== blob.kind
      || binding.ordinal !== blob.ordinal
      || binding.size !== blob.ciphertextSize
      || !same(binding.sha256, decode(blob.ciphertextSha256, 32))
    ) {
      throw new SyncError('SYNC_REJECTED')
    }
  }
}

function readBinding(bytes: Uint8Array): { blobId: string; kind: IncomingBlob['kind']; ordinal: number | null; size: number; sha256: Uint8Array } {
  const fields = walk(bytes, [1, 2, 3, 4, 5], null)
  const kindNumber = fields.numbers.get(2)
  const kind = kindNumber === 1 ? 'RECOGNITION_MANIFEST' : kindNumber === 2 ? 'CONTENT_MANIFEST' : kindNumber === 3 ? 'PHOTO' : null
  if (!kind) throw new SyncError('SYNC_REJECTED')
  const encoded = fields.numbers.get(3)
  const ordinal = unzigzag(kind === 'PHOTO' ? encoded ?? 0 : encoded ?? Number.NaN)
  if (kind === 'PHOTO') {
    if (ordinal < 0 || ordinal > 4) throw new SyncError('SYNC_REJECTED')
  } else if (ordinal !== -1) {
    throw new SyncError('SYNC_REJECTED')
  }
  const size = fields.numbers.get(4)
  const sha = fields.bytes.get(5)
  if (size === undefined || size < 1 || !sha || sha.length !== 32) throw new SyncError('SYNC_REJECTED')
  return {
    blobId: uuidText(fields.bytes.get(1)),
    kind,
    ordinal: kind === 'PHOTO' ? ordinal : null,
    size,
    sha256: sha,
  }
}

function walk(
  bytes: Uint8Array,
  allowed: number[],
  repeatedField: number | null,
  int64Field: number | null = null,
): { numbers: Map<number, number>; bytes: Map<number, Uint8Array>; repeated: Uint8Array[]; int64s: Map<number, bigint> } {
  const numbers = new Map<number, number>()
  const byteFields = new Map<number, Uint8Array>()
  const repeated: Uint8Array[] = []
  const int64s = new Map<number, bigint>()
  let pos = 0
  const integer = (): number => {
    let value = 0
    let factor = 1
    for (let i = 0; i < 5; i++) {
      const piece = bytes[pos++]
      if (piece === undefined) throw new SyncError('SYNC_REJECTED')
      value += (piece & 127) * factor
      if ((piece & 128) === 0) {
        if (value > 0xffffffff) throw new SyncError('SYNC_REJECTED')
        return value
      }
      factor *= 128
    }
    throw new SyncError('SYNC_REJECTED')
  }
  /**
   * T283 scoped signed64 reader for the ROOT timestamp only. Reads at most 10
   * bytes, rejects a 10th-byte payload > 1 (overflow beyond 64 bits) or an
   * unterminated/truncated varint, and converts the uint64 wire value to
   * two's-complement signed64. Every other scalar still uses `integer()`
   * (uint32) above; this does NOT loosen any non-timestamp field.
   */
  const signed64 = (): bigint => {
    let value = 0n
    let factor = 1n
    for (let i = 0; i < 10; i++) {
      const piece = bytes[pos++]
      if (piece === undefined) throw new SyncError('SYNC_REJECTED')
      value += BigInt(piece & 127) * factor
      if ((piece & 128) === 0) {
        if (i === 9 && piece > 1) throw new SyncError('SYNC_REJECTED')
        return value >= (1n << 63n) ? value - (1n << 64n) : value
      }
      factor *= 128n
    }
    throw new SyncError('SYNC_REJECTED')
  }
  while (pos < bytes.length) {
    const tag = integer()
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (!allowed.includes(field)) throw new SyncError('SYNC_REJECTED')
    if (int64Field !== null && field === int64Field) {
      // Root scalar wire0 only; any other wire and any repeat (including a
      // cross-wire repeat) is malformed.
      if (wire !== 0 || int64s.has(field) || numbers.has(field) || byteFields.has(field)) {
        throw new SyncError('SYNC_REJECTED')
      }
      int64s.set(field, signed64())
      continue
    }
    if (wire === 0) {
      if (numbers.has(field)) throw new SyncError('SYNC_REJECTED')
      numbers.set(field, integer())
    } else if (wire === 2) {
      const length = integer()
      const value = bytes.slice(pos, pos + length)
      if (value.length !== length) throw new SyncError('SYNC_REJECTED')
      pos += length
      if (field === repeatedField) repeated.push(value)
      else if (byteFields.has(field)) throw new SyncError('SYNC_REJECTED')
      else byteFields.set(field, value)
    } else {
      throw new SyncError('SYNC_REJECTED')
    }
  }
  return { numbers, bytes: byteFields, repeated, int64s }
}

function unzigzag(value: number): number {
  return (value >>> 1) ^ -(value & 1)
}

function uuidText(bytes: Uint8Array | undefined): string {
  if (!bytes || bytes.length !== 16) throw new SyncError('SYNC_REJECTED')
  const hex = bytesToHex(bytes)
  const text = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  if (!isCanonicalUuid(text)) throw new SyncError('SYNC_REJECTED')
  return text
}

function uuidField(value: unknown): string {
  if (typeof value !== 'string' || !isCanonicalUuid(value)) throw new SyncError('SYNC_REJECTED')
  return value
}

function decode(value: string, exact: number, max = exact): Uint8Array {
  let bytes: Uint8Array
  try {
    bytes = unb64(value)
  } catch {
    throw new SyncError('SYNC_REJECTED')
  }
  if (bytes.length < exact || bytes.length > max || (exact === max && bytes.length !== exact)) {
    throw new SyncError('SYNC_REJECTED')
  }
  return bytes
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function same(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0)
  return diff === 0
}
