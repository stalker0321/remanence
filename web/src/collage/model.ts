import { ProtocolError } from '../protocol/errors'

export const CANVAS_WIDTH = 360
export const CANVAS_HEIGHT = 640
export const CONTRACT_VERSION = 1
export const EXPRESSION_CONTRACT_V3 = 3
export const EXPRESSION_CONTRACT_V4 = 4
export const CROP_MAX_V3 = 10_000
export const BRAND_TEXT = 'Remanence'
export const SENDER_PREFIX = 'From '
export const TEXT_FAMILIES = ['StudySans', 'StudySerif', 'StudyMono', 'Narrow', 'Gothic'] as const
export const MUSIC_MODES_V3 = ['text', 'thumbnail'] as const
export const MUSIC_MODES_V4 = ['text', 'thumbnail', 'inline', 'tile-art', 'tile-text', 'sleeve', 'sleeve-empty', 'spine', 'pulse'] as const
export const MUSIC_COVER_MODES_V4 = ['thumbnail', 'inline', 'tile-art', 'sleeve', 'pulse'] as const
export const MUSIC_ARTWORK_REQUIRED_MODES_V4 = ['thumbnail', 'tile-art', 'sleeve'] as const
export const MUSIC_TEXT_ONLY_MODES_V4 = ['text', 'tile-text', 'sleeve-empty', 'spine'] as const
export const MUSIC_BARE_TEXT_OK_MODES_V4 = ['inline', 'pulse'] as const
export const ARTWORK_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const
export const ARTWORK_PROVIDERS = ['catalog-sample', 'coverartarchive'] as const
export const ARTWORK_HOSTS = ['art.example.org', 'archive.org'] as const

export interface PhotoRef {
  contentId: string
  ordinal: number
  widthPx: number
  heightPx: number
  contentHash: string
}

export interface GeneratorInput {
  ownerId: string
  epoch: bigint
  photos: PhotoRef[]
  note: string | null
  music: { trackId: string; artworkId: string | null } | null
}

export interface CropWindow {
  x: number
  y: number
  width: number
  height: number
}

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export type PhotoShape = 'rect' | 'round' | 'ellipse'

export interface Placement {
  contentId: string
  x: number
  y: number
  width: number
  height: number
  crop: CropWindow | null
  maskId: string | null
  contentRect: Rect | null
  shape: PhotoShape
}

export interface StyleBinding {
  styleId: string
  styleVersion: string
  digest: Uint8Array
}

export interface TextType {
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
}

export interface ArtworkRef {
  provider: string
  url: string
  sha256: Uint8Array
  mimeType: string
  width: number
  height: number
}

export interface MusicRegion {
  x: number
  y: number
  width: number
  height: number
  mode: string
  type: TextType
  artwork: ArtworkRef | null
}

export type ProvenanceKind = 'brand' | 'sender'
export type MarkOrientation = 'horizontal' | 'vertical-up' | 'vertical-down'
export type MarkAlign = 'left' | 'center' | 'right'

export interface ProvenanceMark {
  kind: ProvenanceKind
  text: string
  x: number
  y: number
  width: number
  height: number
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  orientation: MarkOrientation
  align: MarkAlign
}

export type DecorKind = 'carrier' | 'acid-ribbon' | 'print-plane'

export interface DecorPlane {
  kind: DecorKind
  x: number
  y: number
  width: number
  height: number
}

export interface ResolvedExpression {
  canvasVersion: number
  grammarId: string
  grammarVersion: number
  branchId: string
  input: GeneratorInput
  placements: Placement[]
  noteTreatment: string
  diagnostics: string[]
  fontVersion: number
  paletteVersion: number
  expressionContractVersion: number
  noteRegion: Rect | null
  style: StyleBinding | null
  noteType: TextType | null
  musicRegion: MusicRegion | null
  provenance: ProvenanceMark[] | null
  decor: DecorPlane[] | null
}

export type Validation = { ok: true } | { ok: false; reasons: string[] }

export function markLine(size: number): number {
  return Math.ceil(size * 1.02)
}

export function validateResolved(expression: ResolvedExpression): Validation {
  const reasons: string[] = []
  const input = expression.input
  if (input.ownerId.trim() === '') reasons.push('ownerId must not be blank')
  if (input.epoch < 0n) reasons.push('epoch must be >= 0')
  if (input.photos.length < 3 || input.photos.length > 5) reasons.push(`photos.size must be 3..5, got ${input.photos.length}`)
  input.photos.forEach((photo, index) => {
    if (photo.ordinal !== index) reasons.push(`photos[${index}].ordinal must equal ${index}`)
    if (photo.contentId.trim() === '') reasons.push(`photos[${index}].contentId must not be blank`)
    if (looksLikeUri(photo.contentId)) reasons.push(`photos[${index}].contentId must be an opaque id`)
    if (photo.widthPx <= 0 || photo.heightPx <= 0) reasons.push(`photos[${index}] dimensions must be positive`)
    if (!/^[0-9a-f]{64}$/.test(photo.contentHash)) reasons.push(`photos[${index}].contentHash must be 64 lowercase hex chars`)
  })
  if (new Set(input.photos.map((photo) => photo.contentId)).size !== input.photos.length) reasons.push('photos.contentId must be unique')
  if (new Set(input.photos.map((photo) => photo.contentHash)).size !== input.photos.length) reasons.push('photos.contentHash must be unique')
  if (utf8Size(input.note ?? '') > 1000) reasons.push('note must be <= 1000 UTF-8 bytes')
  if (input.music?.trackId.trim() === '') reasons.push('music.trackId must not be blank')
  if (input.music?.artworkId === '') reasons.push('music.artworkId must not be blank')
  if (expression.canvasVersion <= 0) reasons.push('canvasVersion must be positive')
  if (expression.grammarId.trim() === '') reasons.push('grammarId must not be blank')
  if (expression.branchId.trim() === '') reasons.push('branchId must not be blank')
  if (expression.noteTreatment.trim() === '') reasons.push('noteTreatment must not be blank')
  const version = expression.expressionContractVersion
  if (version !== EXPRESSION_CONTRACT_V3 && version !== EXPRESSION_CONTRACT_V4) {
    reasons.push(`expressionContractVersion must be 3 or 4 for this renderer, got ${version}`)
  }
  if (version === EXPRESSION_CONTRACT_V3) {
    if (expression.provenance !== null) reasons.push('v3 expression must not carry provenance')
    if (expression.decor !== null) reasons.push('v3 expression must not carry decor')
    reasons.push(...(expression.style ? validateStyle(expression.style) : ['v3 requires style']))
    reasons.push(...(expression.noteType ? validateText(expression.noteType, 'noteType') : ['v3 requires noteType']))
    if (expression.noteRegion && !fitsCanvas(expression.noteRegion)) reasons.push('v3 noteRegion must fit 360x640')
    const music = expression.musicRegion
    if (music) {
      if (!fitsCanvas(music)) reasons.push('v3 musicRegion must fit 360x640')
      if (!MUSIC_MODES_V3.includes(music.mode as typeof MUSIC_MODES_V3[number])) reasons.push(`v3 musicRegion.mode must be text or thumbnail, got ${music.mode}`)
      reasons.push(...validateText(music.type, 'musicRegion.type'))
      if ((music.mode === 'thumbnail') !== (music.artwork !== null)) reasons.push('v3 musicRegion requires mode thumbnail iff artwork is present')
      if (music.artwork) reasons.push(...validateArtwork(music.artwork))
    }
    if (input.music?.artworkId != null) reasons.push('v3 input.music.artworkId must be null')
  }
  if (version === EXPRESSION_CONTRACT_V4) {
    reasons.push(...(expression.style ? validateStyle(expression.style) : ['v4 requires style']))
    reasons.push(...(expression.noteType ? validateText(expression.noteType, 'noteType') : ['v4 requires noteType']))
    if (expression.noteRegion && !fitsCanvas(expression.noteRegion)) reasons.push('v4 noteRegion must fit 360x640')
    const music = expression.musicRegion
    if (music) {
      if (!fitsCanvas(music)) reasons.push('v4 musicRegion must fit 360x640')
      if (!MUSIC_MODES_V4.includes(music.mode as typeof MUSIC_MODES_V4[number])) reasons.push(`v4 musicRegion.mode is not a frozen mode, got ${music.mode}`)
      reasons.push(...validateText(music.type, 'musicRegion.type'))
      if (MUSIC_ARTWORK_REQUIRED_MODES_V4.includes(music.mode as typeof MUSIC_ARTWORK_REQUIRED_MODES_V4[number]) && !music.artwork) {
        reasons.push(`v4 musicRegion mode ${music.mode} requires artwork`)
      }
      if (MUSIC_TEXT_ONLY_MODES_V4.includes(music.mode as typeof MUSIC_TEXT_ONLY_MODES_V4[number]) && music.artwork) {
        reasons.push(`v4 musicRegion mode ${music.mode} must not carry artwork`)
      }
      if (music.artwork && !MUSIC_COVER_MODES_V4.includes(music.mode as typeof MUSIC_COVER_MODES_V4[number])) {
        reasons.push(`v4 musicRegion artwork requires a cover mode, got ${music.mode}`)
      }
      if (music.artwork) reasons.push(...validateArtwork(music.artwork))
    }
    if (input.music?.artworkId != null) reasons.push('v4 input.music.artworkId must be null')
    if (expression.provenance) {
      if (expression.provenance.length === 0) reasons.push('v4 provenance must not be empty when present')
      if (expression.provenance.length > 2) reasons.push('v4 provenance carries at most brand + sender')
      if (new Set(expression.provenance.map((mark) => mark.kind)).size !== expression.provenance.length) reasons.push('v4 provenance kinds must be unique')
      expression.provenance.forEach((mark, index) => reasons.push(...validateMark(mark, index)))
    }
    if (expression.decor) {
      if (expression.decor.length === 0) reasons.push('v4 decor must not be empty when present')
      if (expression.decor.length > 8) reasons.push('v4 decor carries at most 8 planes')
      expression.decor.forEach((plane, index) => {
        if (!fitsCanvas(plane)) reasons.push(`v4 decor[${index}] must fit 360x640`)
      })
    }
  }
  const authored = expression.input.photos.map((photo) => photo.contentId)
  if (expression.placements.map((placement) => placement.contentId).join('\0') !== authored.join('\0')) {
    reasons.push('placements must cover every authored photo exactly once, in authored order')
  }
  expression.placements.forEach((placement, index) => {
    if (placement.width <= 0 || placement.height <= 0 || !fitsCanvas(placement)) {
      reasons.push(`placements[${index}] must fit 360x640`)
    }
    const crop = placement.crop
    if (!crop) reasons.push(`placements[${index}] v${version} requires crop`)
    else if (crop.x < 0 || crop.y < 0 || crop.width <= 0 || crop.height <= 0 || crop.x + crop.width > CROP_MAX_V3 || crop.y + crop.height > CROP_MAX_V3) {
      reasons.push(`placements[${index}].crop must fit 0..10000`)
    }
    if (placement.maskId === '') reasons.push(`placements[${index}].maskId must not be blank`)
    if (version === EXPRESSION_CONTRACT_V3 && placement.shape !== 'rect') reasons.push(`placements[${index}] v3 must not carry a non-rect shape`)
    const rect = placement.contentRect
    if (rect && (rect.width <= 0 || rect.height <= 0 || rect.x < placement.x || rect.y < placement.y || rect.x + rect.width > placement.x + placement.width || rect.y + rect.height > placement.y + placement.height)) {
      reasons.push(`placements[${index}] contentRect must be positive and inside its placement`)
    }
  })
  return reasons.length === 0 ? { ok: true } : { ok: false, reasons }
}

function validateStyle(style: StyleBinding): string[] {
  const reasons: string[] = []
  if (!/^[a-z0-9-]{1,64}$/.test(style.styleId)) reasons.push('style.styleId must be 1..64 [a-z0-9-]')
  if (style.styleVersion.length < 1 || style.styleVersion.length > 32) reasons.push('style.styleVersion must be 1..32 chars')
  if (style.digest.length !== 32) reasons.push('style.digest must be exactly 32 bytes')
  return reasons
}

function validateText(type: TextType, prefix: string): string[] {
  const reasons: string[] = []
  if (!TEXT_FAMILIES.includes(type.family as typeof TEXT_FAMILIES[number])) reasons.push(`${prefix}.family is not a bundled family`)
  if (type.size < 8 || type.size > 96) reasons.push(`${prefix}.size must be 8..96`)
  if (type.weight !== 400 && type.weight !== 700) reasons.push(`${prefix}.weight must be 400 or 700`)
  if (type.italic && (type.family !== 'StudySerif' || type.weight !== 400)) reasons.push(`${prefix}.italic is only available as StudySerif @400`)
  if (type.line < 8 || type.line > 120) reasons.push(`${prefix}.line must be 8..120`)
  return reasons
}

function validateMark(mark: ProvenanceMark, index: number): string[] {
  const reasons: string[] = []
  const prefix = `provenance[${index}]`
  if (mark.kind === 'brand' && mark.text !== BRAND_TEXT) reasons.push(`${prefix} brand text must be Remanence`)
  if (mark.kind === 'sender' && (!mark.text.startsWith(SENDER_PREFIX) || mark.text.slice(SENDER_PREFIX.length).trim() === '')) {
    reasons.push(`${prefix} sender text must be From <verified handle>`)
  }
  if (!fitsCanvas(mark)) reasons.push(`${prefix} must fit 360x640`)
  if (mark.text.length > 128) reasons.push(`${prefix}.text must be <= 128 chars`)
  reasons.push(...validateText({
    family: mark.family,
    size: mark.size,
    weight: mark.weight,
    italic: mark.italic,
    line: mark.line,
  }, prefix).filter((reason) => !reason.endsWith('line must be 8..120') || mark.line < 8 || mark.line > 120))
  const editorial = mark.size === 64 && mark.line === 70
  if (mark.line !== markLine(mark.size) && !editorial) reasons.push(`${prefix}.line must be ceil(size*1.02)`)
  return reasons
}

function validateArtwork(artwork: ArtworkRef): string[] {
  const reasons: string[] = []
  if (!/^[a-z0-9-]{1,32}$/.test(artwork.provider) || !ARTWORK_PROVIDERS.includes(artwork.provider as typeof ARTWORK_PROVIDERS[number])) {
    reasons.push('artwork.provider must be allowlisted')
  }
  if (!validArtworkUrl(artwork.url)) reasons.push('artwork.url must be stable https without userinfo, query, or fragment')
  if (artwork.sha256.length !== 32) reasons.push('artwork.sha256 must be exactly 32 bytes')
  if (!ARTWORK_MIME_TYPES.includes(artwork.mimeType as typeof ARTWORK_MIME_TYPES[number])) reasons.push('artwork.mimeType is not allowlisted')
  if (artwork.width < 1 || artwork.height < 1 || artwork.width > 4096 || artwork.height > 4096) reasons.push('artwork dimensions must be 1..4096')
  return reasons
}

function validArtworkUrl(url: string): boolean {
  if (url.length < 1 || url.length > 512 || !url.startsWith('https://') || url.includes('?') || url.includes('#')) return false
  const authority = url.slice('https://'.length).split('/')[0] ?? ''
  if (authority.length === 0 || authority.includes('@')) return false
  if (authority.includes(':')) {
    const host = authority.slice(0, authority.lastIndexOf(':'))
    const port = authority.slice(authority.lastIndexOf(':') + 1)
    if (host.length === 0 || port !== '443') return false
  }
  return true
}

function fitsCanvas(rect: Rect): boolean {
  return rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0
    && rect.x + rect.width <= CANVAS_WIDTH && rect.y + rect.height <= CANVAS_HEIGHT
}

function looksLikeUri(value: string): boolean {
  return value.includes('://') || value.startsWith('content:') || value.startsWith('file:') || value.startsWith('android.resource:') || value.startsWith('/')
}

function utf8Size(value: string): number {
  return new TextEncoder().encode(value).length
}

export function requireValid(expression: ResolvedExpression): void {
  const result = validateResolved(expression)
  if (!result.ok) throw new ProtocolError('INVALID_ENCODING')
}
