import { bytesToHex, concat } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { protoBytes, protoVarint, zigzag32 } from '../protocol/proto'
import { projectionHash } from './codec'
import { CollageError } from './errors'
import {
  EXPRESSION_CONTRACT_V4,
  type ProvenanceMark,
  type ResolvedExpression,
  type TextType,
  validateResolved,
} from './model'

/**
 * T259 — pure plaintext `ContentManifest` (protocol_version 2) encoder.
 *
 * Serializes the SAME existing fields the accepted Android
 * `ContentManifestCodec.toProtoExpression`/`build` write (schema
 * `remanence_v1.proto:79-85,110-186,223-282`, `ContentManifestCodec.kt:122-178,
 * 285-417`) from ONE `buildFrozenExpression` (T255) result, using the existing
 * `proto.ts` primitives and the existing BEXPR03 `projectionHash`. It performs
 * NO encryption, auth, grant, epoch/capsule/blob producer policy, no codec/
 * parser/model change, and no plan/crop/measurer reconstruction.
 *
 * Caller inputs are explicit: `capsuleId` (16 bytes), `candidateId` (1..256
 * chars incl. blank rejection), and `blobIdByContentId` (each 16 bytes) — never
 * defaulted, random, or derived from the branch/epoch. Root photo entries are
 * derived from the SAME expression input photo list (ordinal/dims/content), not
 * a second configurable list. `media_type` is the fixed `image/jpeg` label of
 * the already-normalized pipeline (a PRECONDITION of this metadata encoder: it
 * receives no image bytes and cannot validate/transcode JPEG).
 *
 * `expression_hash` is the existing BEXPR03 `projectionHash` over the exact
 * captured data + blob IDs that are serialized (not a GENEX04 hash). Plaintext
 * only; the later ciphertext budget (framing/AEAD overhead) is NOT proven here.
 */

export interface ContentManifestInput {
  expression: ResolvedExpression
  capsuleId: Uint8Array
  candidateId: string
  blobIdByContentId: ReadonlyMap<string, Uint8Array>
}

export type ContentManifestRefusal =
  | 'unsupported'
  | 'expression-invalid'
  | 'capsule-id'
  | 'candidate-id'
  | 'blob-map'
  | 'photo-count'
  | 'photo-dimensions'
  | 'note-size'
  | 'fraction'
  | 'manifest-size'
  | 'contract'

export type ContentManifestResult =
  | { ok: true; bytes: Uint8Array }
  | { ok: false; reason: ContentManifestRefusal; detail?: string }

/** Manifest contract limits (existing Android `ContentManifestCodec.kt:750-766`). */
const MIN_PHOTOS = 3
const MAX_PHOTOS = 5
const MAX_DIMENSION_PX = 10_000
const MAX_NOTE_BYTES = 1000
const MAX_CANDIDATE_ID_CHARS = 256
const BLOB_ID_BYTES = 16
const MEDIA_TYPE_JPEG = 'image/jpeg'
const ARTIFACT_V4 = 3
/** Existing parser `MAX_BYTES` / manifest plaintext contract limit (inclusive). */
const MANIFEST_MAX_BYTES = 65536

const textEncoder = new TextEncoder()

function refuse(reason: ContentManifestRefusal, detail?: string): ContentManifestResult {
  return detail === undefined ? { ok: false, reason } : { ok: false, reason, detail }
}

function utf8(value: string): Uint8Array {
  return textEncoder.encode(value)
}

function int32(value: number): boolean {
  return Number.isInteger(value) && value >= -0x80000000 && value <= 0x7fffffff
}

function uint32(value: number): boolean {
  return Number.isInteger(value) && value >= 0 && value <= 0xffffffff
}

function intRect(rect: { x: number; y: number; width: number; height: number }): boolean {
  return int32(rect.x) && int32(rect.y) && int32(rect.width) && int32(rect.height)
}

function textTypeIntegral(type: TextType): boolean {
  return uint32(type.size) && uint32(type.weight) && uint32(type.line)
}

function sint(field: number, value: number): Uint8Array {
  return protoVarint(field, zigzag32(value))
}

function photoEntry(blobId: Uint8Array, ordinal: number, width: number, height: number): Uint8Array {
  return concat([
    protoBytes(1, blobId),
    protoVarint(2, ordinal),
    protoBytes(3, utf8(MEDIA_TYPE_JPEG)),
    protoVarint(4, width),
    protoVarint(5, height),
  ])
}

function sourceMessage(ordinal: number, blobId: Uint8Array, contentId: string, contentHash: string, width: number, height: number): Uint8Array {
  return concat([
    protoVarint(1, ordinal),
    protoBytes(2, blobId),
    protoBytes(3, utf8(contentId)),
    protoBytes(4, utf8(contentHash)),
    protoVarint(5, width),
    protoVarint(6, height),
  ])
}

function cropMessage(crop: { x: number; y: number; width: number; height: number }): Uint8Array {
  return concat([protoVarint(1, crop.x), protoVarint(2, crop.y), protoVarint(3, crop.width), protoVarint(4, crop.height)])
}

function placementMessage(placement: ResolvedExpression['placements'][number]): Uint8Array {
  const parts: Uint8Array[] = [
    protoBytes(1, utf8(placement.contentId)),
    sint(2, placement.x),
    sint(3, placement.y),
    sint(4, placement.width),
    sint(5, placement.height),
  ]
  if (placement.contentRect) {
    parts.push(protoVarint(6, 1), sint(7, placement.contentRect.x), sint(8, placement.contentRect.y), sint(9, placement.contentRect.width), sint(10, placement.contentRect.height))
  }
  if (placement.crop) parts.push(protoBytes(11, cropMessage(placement.crop)))
  if (placement.maskId !== null) parts.push(protoBytes(12, utf8(placement.maskId)))
  parts.push(protoBytes(13, utf8(placement.shape)))
  return concat(parts)
}

function textTypeMessage(type: TextType): Uint8Array {
  return concat([
    protoBytes(1, utf8(type.family)),
    protoVarint(2, type.size),
    protoVarint(3, type.weight),
    protoVarint(4, type.italic ? 1 : 0),
    protoVarint(5, type.line),
  ])
}

function provenanceMessage(mark: ProvenanceMark): Uint8Array {
  return concat([
    protoBytes(1, utf8(mark.kind)),
    protoBytes(2, utf8(mark.text)),
    sint(3, mark.x),
    sint(4, mark.y),
    sint(5, mark.width),
    sint(6, mark.height),
    protoBytes(7, utf8(mark.family)),
    protoVarint(8, mark.size),
    protoVarint(9, mark.weight),
    protoVarint(10, mark.italic ? 1 : 0),
    protoVarint(11, mark.line),
    protoBytes(12, utf8(mark.orientation)),
    protoBytes(13, utf8(mark.align)),
  ])
}

function decorMessage(plane: NonNullable<ResolvedExpression['decor']>[number]): Uint8Array {
  return concat([protoBytes(1, utf8(plane.kind)), sint(2, plane.x), sint(3, plane.y), sint(4, plane.width), sint(5, plane.height)])
}

function expressionMessage(expression: ResolvedExpression, candidateId: string, expressionHash: string, blobIdByContentId: ReadonlyMap<string, Uint8Array>): Uint8Array {
  const parts: Uint8Array[] = [
    protoVarint(1, ARTIFACT_V4),
    protoBytes(2, utf8(candidateId)),
    protoBytes(3, utf8(expressionHash)),
    protoVarint(4, expression.canvasVersion),
    protoBytes(5, utf8(expression.grammarId)),
    protoVarint(6, expression.grammarVersion),
    protoBytes(7, utf8(expression.branchId)),
    protoBytes(8, utf8(expression.noteTreatment)),
    protoVarint(9, expression.fontVersion),
    protoVarint(10, expression.paletteVersion),
  ]
  if (expression.noteRegion) {
    parts.push(protoVarint(11, 1), sint(12, expression.noteRegion.x), sint(13, expression.noteRegion.y), sint(14, expression.noteRegion.width), sint(15, expression.noteRegion.height))
  }
  for (const photo of expression.input.photos) {
    const blob = blobIdByContentId.get(photo.contentId)
    if (!blob) throw new ProtocolError('INVALID_ENCODING')
    parts.push(protoBytes(16, sourceMessage(photo.ordinal, blob, photo.contentId, photo.contentHash, photo.widthPx, photo.heightPx)))
  }
  for (const placement of expression.placements) parts.push(protoBytes(17, placementMessage(placement)))
  const style = expression.style
  const noteType = expression.noteType
  if (!style || !noteType) throw new ProtocolError('INVALID_ENCODING')
  parts.push(protoBytes(18, concat([protoBytes(1, utf8(style.styleId)), protoBytes(2, utf8(style.styleVersion)), protoBytes(3, style.digest)])))
  parts.push(protoBytes(19, textTypeMessage(noteType)))
  for (const mark of expression.provenance ?? []) parts.push(protoBytes(21, provenanceMessage(mark)))
  for (const plane of expression.decor ?? []) parts.push(protoBytes(22, decorMessage(plane)))
  return concat(parts)
}

export async function encodeContentManifest(input: ContentManifestInput): Promise<ContentManifestResult> {
  const { expression, capsuleId, candidateId, blobIdByContentId } = input
  if (expression.expressionContractVersion !== EXPRESSION_CONTRACT_V4) {
    return refuse('unsupported', `only the V4/GENEX04 (artifact 3) manifest is supported, got ${expression.expressionContractVersion}`)
  }
  if (expression.musicRegion !== null) return refuse('unsupported', 'music is out of scope for the frozen g5 manifest')
  // Manifest contract limits first (existing Android bounds), so a bad
  // dimension/note reports its own typed reason rather than a generic one.
  if (!(capsuleId instanceof Uint8Array) || capsuleId.length !== BLOB_ID_BYTES) return refuse('capsule-id', 'capsuleId must be a 16-byte array')
  if (typeof candidateId !== 'string' || candidateId.trim() === '' || candidateId.length > MAX_CANDIDATE_ID_CHARS) {
    return refuse('candidate-id', 'candidateId must be non-blank and <= 256 chars')
  }
  const photos = expression.input.photos
  if (photos.length < MIN_PHOTOS || photos.length > MAX_PHOTOS) return refuse('photo-count', `${photos.length} photos`)
  for (const photo of photos) {
    if (
      !Number.isInteger(photo.widthPx) || !Number.isInteger(photo.heightPx) ||
      photo.widthPx < 1 || photo.heightPx < 1 ||
      photo.widthPx > MAX_DIMENSION_PX || photo.heightPx > MAX_DIMENSION_PX
    ) {
      return refuse('photo-dimensions', `${photo.contentId} ${photo.widthPx}x${photo.heightPx}`)
    }
  }
  const rawNote = expression.input.note
  const note = rawNote === null || rawNote === '' ? null : rawNote
  if (note !== null && utf8(note).length > MAX_NOTE_BYTES) return refuse('note-size', `${utf8(note).length} utf8 bytes`)
  const validation = validateResolved(expression)
  if (!validation.ok) return refuse('expression-invalid', validation.reasons.join('; '))

  // Blob map must cover EXACTLY the authored sources, each 16 bytes and unique.
  const authored = new Set(photos.map((photo) => photo.contentId))
  if (blobIdByContentId.size !== authored.size) return refuse('blob-map', `map has ${blobIdByContentId.size} entries for ${authored.size} photos`)
  const snapshotBlobs = new Map<string, Uint8Array>()
  const seen = new Set<string>()
  for (const photo of photos) {
    const blob = blobIdByContentId.get(photo.contentId)
    if (!blob || !(blob instanceof Uint8Array) || blob.length !== BLOB_ID_BYTES) return refuse('blob-map', `missing/invalid blob id for ${photo.contentId}`)
    const hex = bytesToHex(blob)
    if (seen.has(hex)) return refuse('blob-map', `duplicate blob id for ${photo.contentId}`)
    seen.add(hex)
    snapshotBlobs.set(photo.contentId, blob.slice())
  }
  for (const key of blobIdByContentId.keys()) {
    if (!authored.has(key)) return refuse('blob-map', `unexpected blob id entry ${key}`)
  }

  // Fractional wire geometry is refused, never floored at encode time.
  for (const placement of expression.placements) {
    if (!intRect(placement)) return refuse('fraction', `placement ${placement.contentId}`)
    if (placement.contentRect && !intRect(placement.contentRect)) return refuse('fraction', `placement contentRect ${placement.contentId}`)
    if (placement.crop && !(uint32(placement.crop.x) && uint32(placement.crop.y) && uint32(placement.crop.width) && uint32(placement.crop.height))) {
      return refuse('fraction', `placement crop ${placement.contentId}`)
    }
    if (placement.maskId !== null && typeof placement.maskId !== 'string') return refuse('contract', 'mask id type')
  }
  if (expression.noteRegion && !intRect(expression.noteRegion)) return refuse('fraction', 'note region')
  for (const mark of expression.provenance ?? []) {
    if (!intRect(mark) || !textTypeIntegral(mark)) return refuse('fraction', `provenance ${mark.kind}`)
  }
  if (expression.noteType && !textTypeIntegral(expression.noteType)) return refuse('fraction', 'note type')
  if (!expression.style || expression.style.digest.length !== 32) return refuse('contract', 'style digest')

  // Snapshot the mutable caller inputs BEFORE the async digest so a caller
  // mutation during `await` cannot desync the hash from the serialized fields
  // or alias the returned/output bytes.
  const snapshotExpression = structuredClone(expression)
  const snapshotCapsule = capsuleId.slice()

  let expressionHash: string
  try {
    expressionHash = await projectionHash(snapshotExpression, candidateId, snapshotBlobs)
  } catch (error) {
    if (error instanceof ProtocolError) return refuse('contract', error.code)
    if (error instanceof CollageError) return refuse('contract', error.code)
    throw error
  }

  try {
    const chunks: Uint8Array[] = [
      protoVarint(1, 2),
      protoBytes(2, snapshotCapsule),
    ]
    for (const photo of snapshotExpression.input.photos) {
      const blob = snapshotBlobs.get(photo.contentId)
      if (!blob) throw new ProtocolError('INVALID_ENCODING')
      chunks.push(protoBytes(3, photoEntry(blob, photo.ordinal, photo.widthPx, photo.heightPx)))
    }
    if (note !== null) chunks.push(protoBytes(4, utf8(note)))
    chunks.push(protoBytes(6, expressionMessage(snapshotExpression, candidateId, expressionHash, snapshotBlobs)))
    const bytes = concat(chunks)
    // Explicit plaintext-output bound: the existing parser's `MAX_BYTES` (and
    // the manifest contract) cap the FINAL manifest at 65536. The projection
    // cap does not bound this serialization (it omits the root note/capsule/
    // PhotoEntry framing), so refuse rather than emit unparseable bytes.
    if (bytes.length > MANIFEST_MAX_BYTES) return refuse('manifest-size', `${bytes.length} bytes`)
    return { ok: true, bytes }
  } catch (error) {
    if (error instanceof ProtocolError) return refuse('contract', error.code)
    if (error instanceof CollageError) return refuse('contract', error.code)
    throw error
  }
}
