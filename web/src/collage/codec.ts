import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { CanonicalReader, CanonicalWriter } from './canonical'
import { CollageError } from './errors'
import {
  CONTRACT_VERSION,
  EXPRESSION_CONTRACT_V3,
  EXPRESSION_CONTRACT_V4,
  type ArtworkRef,
  type DecorKind,
  type DecorPlane,
  type GeneratorInput,
  type MarkAlign,
  type MarkOrientation,
  type MusicRegion,
  type PhotoShape,
  type Placement,
  type ProvenanceKind,
  type ProvenanceMark,
  type ResolvedExpression,
  type TextType,
  requireValid,
} from './model'

const MAGIC_V3 = 'GENEX03'
const MAGIC_V4 = 'GENEX04'
const PROJECTION_V3 = 'BEXPR02'
const PROJECTION_V4 = 'BEXPR03'

export function encodeExpression(expression: ResolvedExpression): Uint8Array {
  requireValid(expression)
  const out = new CanonicalWriter()
  if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V3) {
    writeV3(out, expression)
  } else if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V4) {
    writeV4(out, expression)
  } else {
    throw new CollageError('UNSUPPORTED')
  }
  return out.finish()
}

export function decodeExpression(bytes: Uint8Array): ResolvedExpression {
  const reader = new CanonicalReader(bytes)
  const magic = reader.ascii(7)
  if (magic !== MAGIC_V3 && magic !== MAGIC_V4) throw new CollageError('UNSUPPORTED')
  if (reader.int() !== CONTRACT_VERSION) throw new ProtocolError('INVALID_ENCODING')
  const version = reader.int()
  if (magic === MAGIC_V3 && version !== EXPRESSION_CONTRACT_V3) throw new ProtocolError('INVALID_ENCODING')
  if (magic === MAGIC_V4 && version !== EXPRESSION_CONTRACT_V4) throw new ProtocolError('INVALID_ENCODING')
  const expression = readExpression(reader, version)
  if (!reader.finished) throw new ProtocolError('INVALID_ENCODING')
  requireValid(expression)
  return expression
}

export function projectionBytes(
  expression: ResolvedExpression,
  candidateId: string,
  blobIdByContentId: ReadonlyMap<string, Uint8Array>,
): Uint8Array {
  if (candidateId.trim() === '') throw new ProtocolError('INVALID_ENCODING')
  requireValid(expression)
  const out = new CanonicalWriter()
  if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V3) out.ascii(PROJECTION_V3)
  else if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V4) out.ascii(PROJECTION_V4)
  else throw new CollageError('UNSUPPORTED')
  out.int(expression.expressionContractVersion === EXPRESSION_CONTRACT_V4 ? 3 : 2)
  writeProjectionPrefix(out, expression, candidateId, blobIdByContentId)
  const style = expression.style
  const noteType = expression.noteType
  if (!style || style.digest.length !== 32 || !noteType) throw new ProtocolError('INVALID_ENCODING')
  out.str(style.styleId)
  out.str(style.styleVersion)
  out.raw(style.digest)
  writeTextType(out, noteType)
  writeMusic(out, expression.musicRegion)
  if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V4) {
    writeProvenance(out, expression.provenance)
    writeDecor(out, expression.decor)
  }
  return out.finish()
}

export async function projectionHash(
  expression: ResolvedExpression,
  candidateId: string,
  blobIdByContentId: ReadonlyMap<string, Uint8Array>,
): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', arrayBuffer(projectionBytes(expression, candidateId, blobIdByContentId)))
  return bytesToHex(new Uint8Array(digest))
}

function writeV3(out: CanonicalWriter, expression: ResolvedExpression): void {
  out.ascii(MAGIC_V3)
  out.int(CONTRACT_VERSION)
  out.int(EXPRESSION_CONTRACT_V3)
  writeIdentity(out, expression)
  writePlacements(out, expression.placements, false)
  writeStyleAndMusic(out, expression)
  writeTail(out, expression)
}

function writeV4(out: CanonicalWriter, expression: ResolvedExpression): void {
  out.ascii(MAGIC_V4)
  out.int(CONTRACT_VERSION)
  out.int(EXPRESSION_CONTRACT_V4)
  writeIdentity(out, expression)
  writePlacements(out, expression.placements, true)
  writeStyleAndMusic(out, expression)
  writeProvenance(out, expression.provenance)
  writeDecor(out, expression.decor)
  writeTail(out, expression)
}

function writeIdentity(out: CanonicalWriter, expression: ResolvedExpression): void {
  out.int(expression.canvasVersion)
  out.str(expression.grammarId)
  out.int(expression.grammarVersion)
  out.str(expression.branchId)
  writeInput(out, expression.input)
}

function writeInput(out: CanonicalWriter, input: GeneratorInput): void {
  out.str(input.ownerId)
  out.long(input.epoch)
  out.int(input.photos.length)
  for (const photo of input.photos) {
    out.str(photo.contentId)
    out.int(photo.ordinal)
    out.int(photo.widthPx)
    out.int(photo.heightPx)
    out.str(photo.contentHash)
  }
  if (input.note === null) out.byte(0)
  else {
    out.byte(1)
    out.str(input.note)
  }
  if (input.music === null) out.byte(0)
  else {
    out.byte(1)
    out.str(input.music.trackId)
    if (input.music.artworkId === null) out.byte(0)
    else {
      out.byte(1)
      out.str(input.music.artworkId)
    }
  }
}

function writePlacements(out: CanonicalWriter, placements: Placement[], withShape: boolean): void {
  out.int(placements.length)
  for (const placement of placements) {
    out.str(placement.contentId)
    out.int(placement.x)
    out.int(placement.y)
    out.int(placement.width)
    out.int(placement.height)
    writeRect(out, placement.crop)
    if (placement.maskId === null) out.byte(0)
    else {
      out.byte(1)
      out.str(placement.maskId)
    }
    writeRect(out, placement.contentRect)
    if (withShape) out.str(placement.shape)
  }
}

function writeRect(out: CanonicalWriter, rect: { x: number; y: number; width: number; height: number } | null): void {
  if (!rect) out.byte(0)
  else {
    out.byte(1)
    out.int(rect.x)
    out.int(rect.y)
    out.int(rect.width)
    out.int(rect.height)
  }
}

function writeStyleAndMusic(out: CanonicalWriter, expression: ResolvedExpression): void {
  const style = expression.style
  const noteType = expression.noteType
  if (!style || style.digest.length !== 32 || !noteType) throw new ProtocolError('INVALID_ENCODING')
  out.str(style.styleId)
  out.str(style.styleVersion)
  out.raw(style.digest)
  writeTextType(out, noteType)
  writeMusic(out, expression.musicRegion)
}

function writeMusic(out: CanonicalWriter, music: MusicRegion | null): void {
  if (!music) out.byte(0)
  else {
    out.byte(1)
    out.int(music.x)
    out.int(music.y)
    out.int(music.width)
    out.int(music.height)
    out.str(music.mode)
    writeArtwork(out, music.artwork)
    writeTextType(out, music.type)
  }
}

function writeArtwork(out: CanonicalWriter, artwork: ArtworkRef | null): void {
  if (!artwork) out.byte(0)
  else {
    if (artwork.sha256.length !== 32) throw new ProtocolError('INVALID_ENCODING')
    out.byte(1)
    out.str(artwork.provider)
    out.str(artwork.url)
    out.raw(artwork.sha256)
    out.str(artwork.mimeType)
    out.int(artwork.width)
    out.int(artwork.height)
  }
}

function writeTextType(out: CanonicalWriter, type: TextType): void {
  out.str(type.family)
  out.int(type.size)
  out.int(type.weight)
  out.byte(type.italic ? 1 : 0)
  out.int(type.line)
}

function writeProvenance(out: CanonicalWriter, marks: ProvenanceMark[] | null): void {
  if (!marks) out.byte(0)
  else {
    out.byte(1)
    out.int(marks.length)
    for (const mark of marks) {
      out.str(mark.kind)
      out.str(mark.text)
      out.int(mark.x)
      out.int(mark.y)
      out.int(mark.width)
      out.int(mark.height)
      out.str(mark.family)
      out.int(mark.size)
      out.int(mark.weight)
      out.byte(mark.italic ? 1 : 0)
      out.int(mark.line)
      out.str(mark.orientation)
      out.str(mark.align)
    }
  }
}

function writeDecor(out: CanonicalWriter, planes: DecorPlane[] | null): void {
  if (!planes) out.byte(0)
  else {
    out.byte(1)
    out.int(planes.length)
    for (const plane of planes) {
      out.str(plane.kind)
      out.int(plane.x)
      out.int(plane.y)
      out.int(plane.width)
      out.int(plane.height)
    }
  }
}

function writeTail(out: CanonicalWriter, expression: ResolvedExpression): void {
  out.str(expression.noteTreatment)
  out.int(expression.diagnostics.length)
  for (const diagnostic of expression.diagnostics) out.str(diagnostic)
  out.int(expression.fontVersion)
  out.int(expression.paletteVersion)
}

function writeProjectionPrefix(
  out: CanonicalWriter,
  expression: ResolvedExpression,
  candidateId: string,
  blobIdByContentId: ReadonlyMap<string, Uint8Array>,
): void {
  out.int(expression.canvasVersion)
  out.str(expression.grammarId)
  out.int(expression.grammarVersion)
  out.str(expression.branchId)
  out.str(expression.noteTreatment)
  out.int(expression.fontVersion)
  out.int(expression.paletteVersion)
  writeRect(out, expression.noteRegion)
  out.str(candidateId)
  out.int(expression.input.photos.length)
  for (const photo of expression.input.photos) {
    const blobId = blobIdByContentId.get(photo.contentId)
    if (!blobId || blobId.length !== 16) throw new ProtocolError('INVALID_ENCODING')
    out.int(photo.ordinal)
    out.str(photo.contentId)
    out.str(photo.contentHash)
    out.int(photo.widthPx)
    out.int(photo.heightPx)
    out.raw(blobId)
  }
  out.int(expression.placements.length)
  for (const placement of expression.placements) {
    out.str(placement.contentId)
    out.int(placement.x)
    out.int(placement.y)
    out.int(placement.width)
    out.int(placement.height)
    writeRect(out, placement.contentRect)
    if (!placement.crop) throw new ProtocolError('INVALID_ENCODING')
    out.byte(1)
    out.int(placement.crop.x)
    out.int(placement.crop.y)
    out.int(placement.crop.width)
    out.int(placement.crop.height)
    if (placement.maskId === null) out.byte(0)
    else {
      out.byte(1)
      out.str(placement.maskId)
    }
    if (expression.expressionContractVersion === EXPRESSION_CONTRACT_V4) out.str(placement.shape)
  }
}

function readExpression(reader: CanonicalReader, version: number): ResolvedExpression {
  const canvasVersion = reader.int()
  const grammarId = reader.str()
  const grammarVersion = reader.int()
  const branchId = reader.str()
  const input = readInput(reader)
  const count = boundedCount(reader.int())
  const placements: Placement[] = []
  for (let i = 0; i < count; i++) placements.push(readPlacement(reader, version === EXPRESSION_CONTRACT_V4))
  const styleId = reader.str()
  const styleVersion = reader.str()
  const digest = reader.raw(32)
  const noteType = readTextType(reader)
  const musicRegion = reader.byte() === 0 ? null : readMusic(reader)
  const provenance = version === EXPRESSION_CONTRACT_V4 ? readProvenance(reader) : null
  const decor = version === EXPRESSION_CONTRACT_V4 ? readDecor(reader) : null
  const noteTreatment = reader.str()
  const diagnosticCount = boundedCount(reader.int())
  const diagnostics: string[] = []
  for (let i = 0; i < diagnosticCount; i++) diagnostics.push(reader.str())
  return {
    canvasVersion,
    grammarId,
    grammarVersion,
    branchId,
    input,
    placements,
    noteTreatment,
    diagnostics,
    fontVersion: reader.int(),
    paletteVersion: reader.int(),
    expressionContractVersion: version,
    noteRegion: null,
    style: { styleId, styleVersion, digest },
    noteType,
    musicRegion,
    provenance,
    decor,
  }
}

function readInput(reader: CanonicalReader): GeneratorInput {
  const ownerId = reader.str()
  const epoch = reader.long()
  const count = boundedCount(reader.int())
  const photos = []
  for (let i = 0; i < count; i++) {
    photos.push({
      contentId: reader.str(),
      ordinal: reader.int(),
      widthPx: reader.int(),
      heightPx: reader.int(),
      contentHash: reader.str(),
    })
  }
  const note = reader.byte() === 0 ? null : reader.str()
  const music = reader.byte() === 0 ? null : {
    trackId: reader.str(),
    artworkId: reader.byte() === 0 ? null : reader.str(),
  }
  return { ownerId, epoch, photos, note, music }
}

function readPlacement(reader: CanonicalReader, withShape: boolean): Placement {
  const contentId = reader.str()
  const x = reader.int()
  const y = reader.int()
  const width = reader.int()
  const height = reader.int()
  const crop = readRect(reader)
  const maskId = reader.byte() === 0 ? null : reader.str()
  const contentRect = readRect(reader)
  const shape = withShape ? readShape(reader.str()) : 'rect'
  return { contentId, x, y, width, height, crop, maskId, contentRect, shape }
}

function readRect(reader: CanonicalReader): { x: number; y: number; width: number; height: number } | null {
  if (reader.byte() === 0) return null
  return { x: reader.int(), y: reader.int(), width: reader.int(), height: reader.int() }
}

function readMusic(reader: CanonicalReader): MusicRegion {
  const x = reader.int()
  const y = reader.int()
  const width = reader.int()
  const height = reader.int()
  const mode = reader.str()
  const artwork = reader.byte() === 0 ? null : {
    provider: reader.str(),
    url: reader.str(),
    sha256: reader.raw(32),
    mimeType: reader.str(),
    width: reader.int(),
    height: reader.int(),
  }
  return { x, y, width, height, mode, artwork, type: readTextType(reader) }
}

function readTextType(reader: CanonicalReader): TextType {
  return {
    family: reader.str(),
    size: reader.int(),
    weight: reader.int(),
    italic: reader.byte() === 1,
    line: reader.int(),
  }
}

function readProvenance(reader: CanonicalReader): ProvenanceMark[] | null {
  if (reader.byte() === 0) return null
  const count = boundedCount(reader.int())
  const marks: ProvenanceMark[] = []
  for (let i = 0; i < count; i++) {
    marks.push({
      kind: readKind(reader.str()),
      text: reader.str(),
      x: reader.int(),
      y: reader.int(),
      width: reader.int(),
      height: reader.int(),
      family: reader.str(),
      size: reader.int(),
      weight: reader.int(),
      italic: reader.byte() === 1,
      line: reader.int(),
      orientation: readOrientation(reader.str()),
      align: readAlign(reader.str()),
    })
  }
  return marks
}

function readDecor(reader: CanonicalReader): DecorPlane[] | null {
  if (reader.byte() === 0) return null
  const count = boundedCount(reader.int())
  const planes: DecorPlane[] = []
  for (let i = 0; i < count; i++) {
    planes.push({
      kind: readDecorKind(reader.str()),
      x: reader.int(),
      y: reader.int(),
      width: reader.int(),
      height: reader.int(),
    })
  }
  return planes
}

function readShape(value: string): PhotoShape {
  if (value === 'rect' || value === 'round' || value === 'ellipse') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readKind(value: string): ProvenanceKind {
  if (value === 'brand' || value === 'sender') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readOrientation(value: string): MarkOrientation {
  if (value === 'horizontal' || value === 'vertical-up' || value === 'vertical-down') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readAlign(value: string): MarkAlign {
  if (value === 'left' || value === 'center' || value === 'right') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function readDecorKind(value: string): DecorKind {
  if (value === 'carrier' || value === 'acid-ribbon' || value === 'print-plane') return value
  throw new ProtocolError('INVALID_ENCODING')
}

function boundedCount(value: number): number {
  if (value < 0 || value > 64) throw new ProtocolError('INVALID_ENCODING')
  return value
}
