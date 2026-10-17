import { bytesToHex } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { CollageError } from './errors'
import { projectionHash } from './codec'
import {
  EXPRESSION_CONTRACT_V3,
  EXPRESSION_CONTRACT_V4,
  type ArtworkRef,
  type PhotoShape,
  type Placement,
  type ProvenanceMark,
  type ResolvedExpression,
  type TextType,
  validateResolved,
} from './model'
import type { TrackWords } from './prepare'
import { equalBytes } from './styles'

const MAX_BYTES = 65536
const ARTIFACT_V3 = 2
const ARTIFACT_V4 = 3

export interface ManifestPhoto {
  blobId: Uint8Array
  ordinal: number
  mediaType: string
  width: number
  height: number
}

export interface ParsedManifest {
  capsuleId: Uint8Array
  expression: ResolvedExpression
  candidateId: string
  expressionHash: string
  photos: ManifestPhoto[]
  note: string | null
  track: TrackWords | null
}

interface Part {
  field: number
  value: number | Uint8Array
}

/**
 * ContentManifest with repeated photos, sources, placements, provenance, and decor.
 * Unknown fields fail closed. Expression artifact versions other than 2 (GENEX03)
 * and 3 (GENEX04) are refused before a scene exists.
 */
export async function parseContentManifest(bytes: Uint8Array): Promise<ParsedManifest> {
  const root = readParts(bytes, [1, 2, 3, 4, 5, 6, 7], [3])
  if (root.some((part) => part.field === 5)) throw new ProtocolError('INVALID_PROTO')
  const version = requiredNumber(root, 1)
  if (version === 1) throw new CollageError('UNSUPPORTED')
  if (version !== 2) throw new ProtocolError('UNKNOWN_VERSION')
  const capsuleId = requiredBytes(root, 2)
  if (capsuleId.length !== 16) throw new ProtocolError('INVALID_ENCODING')
  const photos = many(root, 3).map((part) => parsePhoto(asBytes(part)))
  if (photos.length < 3 || photos.length > 5) throw new ProtocolError('INVALID_ENCODING')
  photos.forEach((photo, index) => {
    if (photo.ordinal !== index || photo.mediaType !== 'image/jpeg') throw new ProtocolError('INVALID_ENCODING')
  })
  if (new Set(photos.map((photo) => bytesToHex(photo.blobId))).size !== photos.length) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  const notePart = optional(root, 4)
  const noteText = notePart ? text(asBytes(notePart)) : ''
  const note = noteText === '' ? null : noteText
  const trackPart = optional(root, 7)
  const track = trackPart ? parseTrack(asBytes(trackPart)) : null
  const expressionPart = optional(root, 6)
  if (!expressionPart) throw new ProtocolError('INVALID_ENCODING')
  const parsed = parseExpression(asBytes(expressionPart), photos, note)
  const recomputed = await projectionHash(parsed.expression, parsed.candidateId, parsed.blobs)
  if (!equalBytes(utf8(recomputed), utf8(parsed.expressionHash))) throw new CollageError('INTEGRITY')
  return {
    capsuleId,
    expression: parsed.expression,
    candidateId: parsed.candidateId,
    expressionHash: parsed.expressionHash,
    photos,
    note,
    track,
  }
}

function parsePhoto(bytes: Uint8Array): ManifestPhoto {
  const fields = readParts(bytes, [1, 2, 3, 4, 5], [])
  const blobId = requiredBytes(fields, 1)
  if (blobId.length !== 16) throw new ProtocolError('INVALID_ENCODING')
  return {
    blobId,
    ordinal: requiredNumber(fields, 2),
    mediaType: text(requiredBytes(fields, 3)),
    width: requiredNumber(fields, 4),
    height: requiredNumber(fields, 5),
  }
}

function parseTrack(bytes: Uint8Array): TrackWords {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6], [])
  if (requiredNumber(fields, 1) !== 1) throw new ProtocolError('UNKNOWN_VERSION')
  const title = text(requiredBytes(fields, 3))
  const artistDisplay = text(requiredBytes(fields, 4))
  if (title.length < 1 || title.length > 100 || artistDisplay.length < 1 || artistDisplay.length > 200) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  return { title, artistDisplay }
}

function parseExpression(bytes: Uint8Array, photos: ManifestPhoto[], note: string | null): {
  expression: ResolvedExpression
  candidateId: string
  expressionHash: string
  blobs: Map<string, Uint8Array>
} {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22], [16, 17, 21, 22])
  const artifact = requiredNumber(fields, 1)
  if (artifact !== ARTIFACT_V3 && artifact !== ARTIFACT_V4) throw new CollageError('UNSUPPORTED')
  const finishedV4 = artifact === ARTIFACT_V4
  if (!finishedV4 && (many(fields, 21).length > 0 || many(fields, 22).length > 0)) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  const candidateId = text(requiredBytes(fields, 2))
  const expressionHash = text(requiredBytes(fields, 3))
  if (candidateId.trim() === '' || candidateId.length > 256 || !/^[0-9a-f]{64}$/.test(expressionHash)) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  const sources = many(fields, 16).map((part) => parseSource(asBytes(part)))
  const placements = many(fields, 17).map((part) => parsePlacement(asBytes(part), finishedV4))
  if (sources.length !== photos.length || placements.length !== photos.length) throw new ProtocolError('INVALID_ENCODING')
  const blobs = new Map<string, Uint8Array>()
  sources.forEach((source, index) => {
    const photo = photos[index]
    if (!photo || source.ordinal !== index || !equalBytes(source.blobId, photo.blobId)) {
      throw new ProtocolError('INVALID_ENCODING')
    }
    if (blobs.has(source.contentId)) throw new ProtocolError('INVALID_ENCODING')
    blobs.set(source.contentId, source.blobId)
  })
  if (placements.map((placement) => placement.contentId).join('\0') !== sources.map((source) => source.contentId).join('\0')) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  const styleFields = readParts(requiredBytes(fields, 18), [1, 2, 3], [])
  const digest = requiredBytes(styleFields, 3)
  if (digest.length !== 32) throw new ProtocolError('INVALID_ENCODING')
  const noteType = parseText(requiredBytes(fields, 19))
  const musicPart = optional(fields, 20)
  const music = musicPart ? parseMusic(asBytes(musicPart)) : null
  const provenance = finishedV4 && many(fields, 21).length > 0
    ? many(fields, 21).map((part) => parseMark(asBytes(part)))
    : null
  const decor = finishedV4 && many(fields, 22).length > 0
    ? many(fields, 22).map((part) => parseDecor(asBytes(part)))
    : null
  const expression: ResolvedExpression = {
    canvasVersion: requiredNumber(fields, 4),
    grammarId: text(requiredBytes(fields, 5)),
    grammarVersion: requiredNumber(fields, 6),
    branchId: text(requiredBytes(fields, 7)),
    input: {
      ownerId: 'receiver',
      epoch: 0n,
      photos: sources.map((source) => ({
        contentId: source.contentId,
        ordinal: source.ordinal,
        widthPx: source.width,
        heightPx: source.height,
        contentHash: source.contentHash,
      })),
      note,
      music: null,
    },
    placements,
    noteTreatment: text(requiredBytes(fields, 8)),
    diagnostics: [],
    fontVersion: requiredNumber(fields, 9),
    paletteVersion: requiredNumber(fields, 10),
    expressionContractVersion: finishedV4 ? EXPRESSION_CONTRACT_V4 : EXPRESSION_CONTRACT_V3,
    noteRegion: flag(fields, 11) ? {
      x: sint(fields, 12),
      y: sint(fields, 13),
      width: sint(fields, 14),
      height: sint(fields, 15),
    } : null,
    style: {
      styleId: text(requiredBytes(styleFields, 1)),
      styleVersion: text(requiredBytes(styleFields, 2)),
      digest,
    },
    noteType,
    musicRegion: music,
    provenance,
    decor,
  }
  if (!validateResolved(expression).ok) throw new ProtocolError('INVALID_ENCODING')
  return { expression, candidateId, expressionHash, blobs }
}

function parseSource(bytes: Uint8Array): { ordinal: number; blobId: Uint8Array; contentId: string; contentHash: string; width: number; height: number } {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6], [])
  const blobId = requiredBytes(fields, 2)
  const contentHash = text(requiredBytes(fields, 4))
  if (blobId.length !== 16 || !/^[0-9a-f]{64}$/.test(contentHash)) throw new ProtocolError('INVALID_ENCODING')
  return {
    ordinal: requiredNumber(fields, 1),
    blobId,
    contentId: text(requiredBytes(fields, 3)),
    contentHash,
    width: requiredNumber(fields, 5),
    height: requiredNumber(fields, 6),
  }
}

function parsePlacement(bytes: Uint8Array, v4: boolean): Placement {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13], [])
  if (!v4 && optional(fields, 13)) throw new ProtocolError('INVALID_ENCODING')
  const cropPart = optional(fields, 11)
  if (!cropPart) throw new ProtocolError('INVALID_ENCODING')
  const cropFields = readParts(asBytes(cropPart), [1, 2, 3, 4], [])
  const shape = v4 ? readShape(text(requiredBytes(fields, 13))) : 'rect'
  return {
    contentId: text(requiredBytes(fields, 1)),
    x: sint(fields, 2),
    y: sint(fields, 3),
    width: sint(fields, 4),
    height: sint(fields, 5),
    crop: {
      x: numberOrZero(cropFields, 1),
      y: numberOrZero(cropFields, 2),
      width: numberOrZero(cropFields, 3),
      height: numberOrZero(cropFields, 4),
    },
    maskId: optional(fields, 12) ? text(requiredBytes(fields, 12)) : null,
    contentRect: flag(fields, 6) ? {
      x: sint(fields, 7),
      y: sint(fields, 8),
      width: sint(fields, 9),
      height: sint(fields, 10),
    } : null,
    shape,
  }
}

function parseMusic(bytes: Uint8Array): ResolvedExpression['musicRegion'] {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6, 7], [])
  const art = optional(fields, 7)
  return {
    x: sint(fields, 1),
    y: sint(fields, 2),
    width: sint(fields, 3),
    height: sint(fields, 4),
    mode: text(requiredBytes(fields, 5)),
    type: parseText(requiredBytes(fields, 6)),
    artwork: art ? parseArtwork(asBytes(art)) : null,
  }
}

function parseArtwork(bytes: Uint8Array): ArtworkRef {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6], [])
  const sha256 = requiredBytes(fields, 3)
  if (sha256.length !== 32) throw new ProtocolError('INVALID_ENCODING')
  return {
    provider: text(requiredBytes(fields, 1)),
    url: text(requiredBytes(fields, 2)),
    sha256,
    mimeType: text(requiredBytes(fields, 4)),
    width: requiredNumber(fields, 5),
    height: requiredNumber(fields, 6),
  }
}

function parseText(bytes: Uint8Array): TextType {
  const fields = readParts(bytes, [1, 2, 3, 4, 5], [])
  return {
    family: text(requiredBytes(fields, 1)),
    size: requiredNumber(fields, 2),
    weight: requiredNumber(fields, 3),
    italic: flag(fields, 4),
    line: requiredNumber(fields, 5),
  }
}

function parseMark(bytes: Uint8Array): ProvenanceMark {
  const fields = readParts(bytes, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13], [])
  return {
    kind: readKind(text(requiredBytes(fields, 1))),
    text: text(requiredBytes(fields, 2)),
    x: sint(fields, 3),
    y: sint(fields, 4),
    width: sint(fields, 5),
    height: sint(fields, 6),
    family: text(requiredBytes(fields, 7)),
    size: requiredNumber(fields, 8),
    weight: requiredNumber(fields, 9),
    italic: flag(fields, 10),
    line: requiredNumber(fields, 11),
    orientation: readOrientation(text(requiredBytes(fields, 12))),
    align: readAlign(text(requiredBytes(fields, 13))),
  }
}

function parseDecor(bytes: Uint8Array): NonNullable<ResolvedExpression['decor']>[number] {
  const fields = readParts(bytes, [1, 2, 3, 4, 5], [])
  const kind = text(requiredBytes(fields, 1))
  if (kind !== 'carrier' && kind !== 'acid-ribbon' && kind !== 'print-plane') throw new ProtocolError('INVALID_ENCODING')
  return { kind, x: sint(fields, 2), y: sint(fields, 3), width: sint(fields, 4), height: sint(fields, 5) }
}

function readShape(value: string): PhotoShape {
  if (value === 'rect' || value === 'round' || value === 'ellipse') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readKind(value: string): ProvenanceMark['kind'] {
  if (value === 'brand' || value === 'sender') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readOrientation(value: string): ProvenanceMark['orientation'] {
  if (value === 'horizontal' || value === 'vertical-up' || value === 'vertical-down') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readAlign(value: string): ProvenanceMark['align'] {
  if (value === 'left' || value === 'center' || value === 'right') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readParts(bytes: Uint8Array, allowed: readonly number[], repeated: readonly number[]): Part[] {
  if (bytes.length > MAX_BYTES) throw new ProtocolError('OVERSIZED')
  const out: Part[] = []
  const seen = new Set<number>()
  let pos = 0
  const integer = (): number => {
    let value = 0
    let factor = 1
    for (let i = 0; i < 5; i++) {
      if (pos >= bytes.length) throw new ProtocolError('INVALID_PROTO')
      const piece = bytes[pos++] ?? 0
      value += (piece & 127) * factor
      if ((piece & 128) === 0) {
        if (!Number.isSafeInteger(value) || value > 0xffffffff) throw new ProtocolError('INVALID_PROTO')
        return value
      }
      factor *= 128
    }
    throw new ProtocolError('INVALID_PROTO')
  }
  while (pos < bytes.length) {
    const tag = integer()
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (!allowed.includes(field)) throw new ProtocolError('INVALID_PROTO')
    if (!repeated.includes(field) && seen.has(field)) throw new ProtocolError('INVALID_PROTO')
    seen.add(field)
    if (wire === 0) out.push({ field, value: integer() })
    else if (wire === 2) {
      const length = integer()
      if (length > bytes.length - pos) throw new ProtocolError('INVALID_PROTO')
      out.push({ field, value: bytes.slice(pos, pos + length) })
      pos += length
    } else {
      throw new ProtocolError('INVALID_PROTO')
    }
  }
  return out
}

function many(list: Part[], field: number): Part[] {
  return list.filter((part) => part.field === field)
}

function optional(list: Part[], field: number): Part | undefined {
  return list.find((part) => part.field === field)
}

function requiredNumber(list: Part[], field: number): number {
  const part = optional(list, field)
  if (!part || typeof part.value !== 'number') throw new ProtocolError('INVALID_PROTO')
  return part.value
}

function numberOrZero(list: Part[], field: number): number {
  const part = optional(list, field)
  if (!part) return 0
  if (typeof part.value !== 'number') throw new ProtocolError('INVALID_PROTO')
  return part.value
}

function requiredBytes(list: Part[], field: number): Uint8Array {
  const part = optional(list, field)
  if (!part) throw new ProtocolError('INVALID_PROTO')
  return asBytes(part)
}

function asBytes(part: Part): Uint8Array {
  if (!(part.value instanceof Uint8Array)) throw new ProtocolError('INVALID_PROTO')
  return part.value
}

function flag(list: Part[], field: number): boolean {
  const part = optional(list, field)
  if (!part) return false
  if (typeof part.value !== 'number' || (part.value !== 0 && part.value !== 1)) throw new ProtocolError('INVALID_PROTO')
  return part.value === 1
}

function sint(list: Part[], field: number): number {
  const part = optional(list, field)
  if (!part) return 0
  if (typeof part.value !== 'number') throw new ProtocolError('INVALID_PROTO')
  const n = part.value >>> 0
  return (n >>> 1) ^ -(n & 1)
}

function text(bytes: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new ProtocolError('INVALID_ENCODING')
  }
}

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value)
}
