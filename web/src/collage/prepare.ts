import { fontFile, FONT_VERSION } from './fonts'
import {
  bandLayout,
  carrierBorderWidth,
  decorFill,
  isTransparentMusicBand,
  musicLayout,
  printPlaneShadow,
  spineCrossBudget,
  spineReadingBudget,
  SPINE_COPY_GAP,
  type BandLayout,
} from './geometry'
import {
  CANVAS_HEIGHT,
  CANVAS_WIDTH,
  EXPRESSION_CONTRACT_V3,
  EXPRESSION_CONTRACT_V4,
  MUSIC_COVER_MODES_V4,
  type PhotoShape,
  type ProvenanceMark,
  type Rect,
  type ResolvedExpression,
  validateResolved,
} from './model'
import { equalBytes, opaqueColor, PALETTE_VERSION, styleById, styleDigest } from './styles'
import { layoutCreatorNote, type CreatorNoteLayout } from './creatorNote'

export interface SourcePhoto {
  width: number
  height: number
}

export interface ScenePhoto {
  contentId: string
  destination: Rect
  source: Rect
  shape: PhotoShape
}

export interface SceneText {
  text: string
  region: Rect
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  color: number
  /**
   * Canonical wrapped rows for the note (T243): the ONE `creatorNote` wrap
   * used for both the sealed-region recursion and the per-row paint. Absent
   * only on non-note producers (e.g. the creator presentation `Scene`).
   */
  noteLayout?: CreatorNoteLayout
}

export interface SceneMusic {
  title: string
  artist: string
  text: string
  region: Rect
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  background: number
  color: number
  transparentBackground: boolean
  mode: string
  vertical: boolean
  copyRegion: Rect
  playRegion: Rect | null
}

export interface SceneArtwork {
  region: Rect
  background: number
}

export interface SceneProvenance {
  kind: ProvenanceMark['kind']
  text: string
  region: Rect
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  orientation: ProvenanceMark['orientation']
  align: ProvenanceMark['align']
  color: number
  tracking: number
}

export interface SceneDecor {
  kind: 'carrier' | 'acid-ribbon' | 'print-plane'
  region: Rect
  background: number
  border: number | null
  shadow: boolean
  borderWidth: number
}

export interface Scene {
  canvasWidth: number
  canvasHeight: number
  styleId: string
  grammarId: string
  paper: number
  ink: number
  sound: number
  soundInk: number
  photos: ScenePhoto[]
  note: SceneText | null
  music: SceneMusic | null
  artwork: SceneArtwork | null
  provenance: SceneProvenance[]
  decor: SceneDecor[]
}

export type SceneResult = { kind: 'ready'; scene: Scene } | { kind: 'rejected'; reason: string }

export interface TextMeasureRequest {
  text: string
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  maxWidth: number
  maxHeight: number
  softWrap?: boolean
}

export interface TextMeasurement {
  width: number
  height: number
  hasVisualOverflow: boolean
}

export interface TextMeasurer {
  measure(request: TextMeasureRequest): TextMeasurement
}

export interface TrackWords {
  title: string
  artistDisplay: string
}

const CROP_MAX = 10_000

export function sourceRect(crop: { x: number; y: number; width: number; height: number }, sourceWidth: number, sourceHeight: number): Rect | null {
  if (sourceWidth <= 0 || sourceHeight <= 0) return null
  const left = mulDiv(crop.x, sourceWidth, CROP_MAX)
  const top = mulDiv(crop.y, sourceHeight, CROP_MAX)
  const right = mulDiv(crop.x + crop.width, sourceWidth, CROP_MAX)
  const bottom = mulDiv(crop.y + crop.height, sourceHeight, CROP_MAX)
  if (left < 0 || top < 0 || right > sourceWidth || bottom > sourceHeight) return null
  const width = right - left
  const height = bottom - top
  if (width < 1 || height < 1) return null
  return { x: left, y: top, width, height }
}

export async function prepare(
  expression: ResolvedExpression,
  note: string | null,
  track: TrackWords | null,
  sources: ReadonlyMap<string, SourcePhoto>,
  measurer: TextMeasurer,
  artwork: SourcePhoto | null = null,
): Promise<SceneResult> {
  if (expression.expressionContractVersion !== EXPRESSION_CONTRACT_V3 && expression.expressionContractVersion !== EXPRESSION_CONTRACT_V4) {
    return reject(`collage renderer requires a contract V3 or V4 expression, got ${expression.expressionContractVersion}`)
  }
  if (!validateResolved(expression).ok) return reject('expression artifact failed validation')
  const noteText = note && note.trim() !== '' ? note : null
  const sealedNote = expression.input.note && expression.input.note.trim() !== '' ? expression.input.note : null
  if (noteText !== sealedNote) return reject('manifest note does not match the sealed expression note')
  if ((noteText !== null) !== (expression.noteRegion !== null)) {
    return reject(noteText ? 'non-empty note requires a sealed note region' : 'sealed note region requires a non-empty note')
  }
  const binding = expression.style
  if (!binding) return reject('expression is missing its style binding')
  const style = styleById(binding.styleId)
  if (!style) return reject(`style ${binding.styleId} is not a bundled collage style`)
  if (binding.styleVersion !== style.version) return reject('style version does not match the bundled style record')
  const expected = await styleDigest(style.id)
  if (!expected || !equalBytes(binding.digest, expected)) return reject('style digest does not match the bundled style record')
  if (expression.fontVersion !== FONT_VERSION) return reject(`unsupported fontVersion ${expression.fontVersion}`)
  if (expression.paletteVersion !== PALETTE_VERSION) return reject(`unsupported paletteVersion ${expression.paletteVersion}`)
  const noteType = expression.noteType
  if (!noteType || !fontFile(noteType.family, noteType.weight, noteType.italic)) {
    return reject('font face for the note is not bundled')
  }
  const musicRegion = expression.musicRegion
  if (musicRegion && !fontFile(musicRegion.type.family, musicRegion.type.weight, musicRegion.type.italic)) {
    return reject('font face for the music is not bundled')
  }
  if ((musicRegion !== null) !== (track !== null)) return reject('music region and track snapshot must match')
  const isV4 = expression.expressionContractVersion === EXPRESSION_CONTRACT_V4
  for (const [index, placement] of expression.placements.entries()) {
    if (placement.maskId !== null) return reject(`placement ${index} carries a mask`)
    if (placement.contentRect !== null) return reject(`placement ${index} carries a contentRect`)
    if (!placement.crop) return reject(`placement ${index} is missing its crop`)
    if (!inside(rectOf(placement))) return reject(`placement ${index} is outside the 360x640 canvas`)
  }
  if (expression.noteRegion && !inside(expression.noteRegion)) return reject('note region is outside the 360x640 canvas')
  if (musicRegion && !inside(musicRegion)) return reject('music region is outside the 360x640 canvas')
  for (const [index, mark] of (expression.provenance ?? []).entries()) {
    if (!inside(mark)) return reject(`provenance ${index} is outside the 360x640 canvas`)
  }
  for (const [index, plane] of (expression.decor ?? []).entries()) {
    if (!inside(plane)) return reject(`decor ${index} is outside the 360x640 canvas`)
  }
  const authored = expression.input.photos.map((photo) => photo.contentId)
  if (!sameSet(sources.keys(), authored)) return reject('source metadata does not match authored content')
  const photos: ScenePhoto[] = []
  for (const [index, placement] of expression.placements.entries()) {
    const source = sources.get(placement.contentId)
    if (!source || source.width <= 0 || source.height <= 0) return reject(`invalid source dimensions for ${placement.contentId}`)
    const cropped = sourceRect(placement.crop!, source.width, source.height)
    if (!cropped) return reject(`placement ${index} crop is outside its source bitmap`)
    photos.push({ contentId: placement.contentId, destination: rectOf(placement), source: cropped, shape: placement.shape })
  }
  if (photos.map((photo) => photo.contentId).join('\0') !== authored.join('\0')) {
    return reject('placements must match authored content order')
  }
  let noteScene: SceneText | null = null
  if (noteText && expression.noteRegion) {
    const region = expression.noteRegion
    // The note is measured with the SAME canonical word wrap the painter
    // uses (unwrapped line-width primitive via `softWrap:false`), so the
    // sealed region check and the painted rows are one layout — never a
    // coarse non-wrapping estimate that lets a clipped multi-line note
    // through as "ready".
    const face = {
      font: noteType.family,
      weight: noteType.weight,
      italic: noteType.italic,
      size: noteType.size,
      line: noteType.line,
    }
    const measureLineWidth = (lineText: string): number =>
      measurer.measure({
        text: lineText,
        family: noteType.family,
        size: noteType.size,
        weight: noteType.weight,
        italic: noteType.italic,
        line: noteType.line,
        maxWidth: Number.POSITIVE_INFINITY,
        maxHeight: Number.POSITIVE_INFINITY,
        softWrap: false,
      }).width
    const layout = layoutCreatorNote(noteText, face, region, measureLineWidth)
    if (layout.overflow) return reject('note does not fit its sealed region')
    noteScene = {
      ...textLayer(noteText, region, noteType, opaqueColor(style.palette.ink)),
      noteLayout: layout,
    }
  }
  let musicScene: SceneMusic | null = null
  let artworkScene: SceneArtwork | null = null
  if (musicRegion && track) {
    const type = musicRegion.type
    const descriptor = musicRegion.artwork
    const verified = descriptor !== null && artwork !== null && artwork.width === descriptor.width && artwork.height === descriptor.height
    const transparent = isV4 && isTransparentMusicBand(expression.grammarId, style.id)
    const background = opaqueColor(transparent ? style.palette.paper : style.palette.sound)
    const color = opaqueColor(transparent ? style.palette.ink : style.palette.soundInk)
    if (verified && !isV4) {
      artworkScene = { region: musicRegion, background: opaqueColor(style.palette.sound) }
    } else if (isV4 && verified && MUSIC_COVER_MODES_V4.includes(musicRegion.mode as typeof MUSIC_COVER_MODES_V4[number])) {
      const placement = musicLayout(musicRegion, expression.grammarId, musicRegion.mode, true)
      if (!placement) return reject('music cover does not fit its sealed region')
      const text = `${track.title}\n${track.artistDisplay}`
      const measurement = measurer.measure({ ...measureOf(text, type), maxWidth: placement.copy.width, maxHeight: placement.copy.height })
      if (!fits(measurement, placement.copy)) return reject('music does not fit its sealed region')
      musicScene = musicLayer(track, text, musicRegion, type, background, color, transparent, musicRegion.mode, false, placement.copy, placement.play)
      artworkScene = { region: musicRegion, background: opaqueColor(style.palette.sound) }
    } else if (isV4 && musicRegion.mode === 'spine') {
      const placement = musicLayout(musicRegion, expression.grammarId, musicRegion.mode, false)
      if (!placement) return reject('music control does not fit its sealed region')
      const band = bandLayout(expression.grammarId, 'spine', musicRegion.height)
      const reading = Math.max(0, spineReadingBudget(musicRegion, band))
      const cross = Math.max(0, spineCrossBudget(musicRegion, band))
      const titleMeasurement = track.title.trim() === '' ? null : measurer.measure({
        ...measureOf(track.title, type),
        maxWidth: reading,
        maxHeight: cross,
        softWrap: false,
      })
      const artistMeasurement = track.artistDisplay.trim() === '' ? null : measurer.measure({
        text: track.artistDisplay,
        family: 'StudySans',
        size: 11,
        weight: 400,
        italic: false,
        line: 13,
        maxWidth: reading,
        maxHeight: cross,
        softWrap: false,
      })
      if (!spineFits(musicRegion, band, titleMeasurement, artistMeasurement)) return reject('music does not fit its sealed region')
      musicScene = musicLayer(track, `${track.title}\n${track.artistDisplay}`, musicRegion, type, background, color, transparent, musicRegion.mode, true, musicRegion, placement.play)
    } else {
      const placement = musicLayout(musicRegion, expression.grammarId, musicRegion.mode, false)
      if (!placement) return reject('music control does not fit its sealed region')
      const text = `${track.title}\n${track.artistDisplay}`
      const measurement = measurer.measure({ ...measureOf(text, type), maxWidth: placement.copy.width, maxHeight: placement.copy.height })
      if (!fits(measurement, placement.copy)) return reject('music does not fit its sealed region')
      musicScene = musicLayer(track, text, musicRegion, type, background, color, transparent, musicRegion.mode, false, placement.copy, placement.play)
    }
  }
  const grammarNumber = expression.grammarId.replace(/^g/, '')
  const provenance: SceneProvenance[] = []
  for (const [index, mark] of (expression.provenance ?? []).entries()) {
    if (!fontFile(mark.family, mark.weight, mark.italic)) return reject('font face for provenance is not bundled')
    const horizontal = mark.orientation === 'horizontal'
    const measurement = measurer.measure({
      text: mark.text,
      family: mark.family,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.line,
      maxWidth: horizontal ? mark.width : mark.height,
      maxHeight: horizontal ? mark.height : mark.width,
    })
    const maxWidth = horizontal ? mark.width : mark.height
    const maxHeight = horizontal ? mark.height : mark.width
    if (measurement.hasVisualOverflow || measurement.width < 1 || measurement.height < 1 || measurement.width > maxWidth || measurement.height > maxHeight) {
      return reject(`provenance ${index} does not fit its sealed region`)
    }
    const brandOnSound = mark.kind === 'brand' && (grammarNumber === '23' || grammarNumber === '24')
    provenance.push({
      kind: mark.kind,
      text: mark.text,
      region: rectOf(mark),
      family: mark.family,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.line,
      orientation: mark.orientation,
      align: mark.align,
      color: opaqueColor(brandOnSound ? style.palette.soundInk : style.palette.ink),
      tracking: mark.kind === 'brand' ? style.tracking : 0,
    })
  }
  const decor: SceneDecor[] = []
  for (const plane of expression.decor ?? []) {
    const fill = decorFill(plane.kind)
    const shadow = plane.kind === 'print-plane' && printPlaneShadow(expression.grammarId, style.surfaceMode)
    const edgeBorder = plane.kind === 'print-plane' && !shadow
    const palette = style.palette
    const role = (name: 'surface' | 'sound' | 'edge') => opaqueColor(palette[name])
    decor.push({
      kind: plane.kind,
      region: rectOf(plane),
      background: role(fill.background),
      border: edgeBorder ? role('edge') : fill.border ? role(fill.border) : null,
      shadow,
      borderWidth: plane.kind === 'carrier' ? carrierBorderWidth(style.surfaceMode) : 1,
    })
  }
  return {
    kind: 'ready',
    scene: {
      canvasWidth: CANVAS_WIDTH,
      canvasHeight: CANVAS_HEIGHT,
      styleId: style.id,
      grammarId: expression.grammarId,
      paper: opaqueColor(style.palette.paper),
      ink: opaqueColor(style.palette.ink),
      sound: opaqueColor(style.palette.sound),
      soundInk: opaqueColor(style.palette.soundInk),
      photos,
      note: noteScene,
      music: musicScene,
      artwork: artworkScene,
      provenance,
      decor,
    },
  }
}

export function spineCopyPlacements(region: Rect, band: BandLayout, titleAdvance: number, titleLineBox: number, artistLineBox: number): { title: Rect; artist: Rect } {
  const readingTop = region.y + band.padTop + 34 + band.gap
  const contentWidth = spineCrossBudget(region, band)
  const copyCross = Math.max(titleLineBox, artistLineBox, 0)
  const copyLeft = region.x + band.padSide + Math.floor((contentWidth - copyCross) / 2)
  const copyRight = copyLeft + copyCross
  return {
    title: { x: copyRight, y: readingTop, width: titleAdvance, height: titleLineBox },
    artist: { x: copyRight, y: readingTop + titleAdvance + SPINE_COPY_GAP, width: 0, height: artistLineBox },
  }
}

function spineFits(region: Rect, band: BandLayout, title: TextMeasurement | null, artist: TextMeasurement | null): boolean {
  const reading = spineReadingBudget(region, band)
  const cross = spineCrossBudget(region, band)
  if (reading < 1 || cross < 1 || (title === null && artist === null)) return false
  let used = title && artist ? SPINE_COPY_GAP : 0
  for (const run of [title, artist]) {
    if (!run) continue
    used += run.width
    if (run.hasVisualOverflow || run.width < 1 || run.height < 1 || run.height > cross) return false
  }
  return used <= reading
}

function measureOf(text: string, type: { family: string; size: number; weight: number; italic: boolean; line: number }): Omit<TextMeasureRequest, 'maxWidth' | 'maxHeight'> {
  return { text, family: type.family, size: type.size, weight: type.weight, italic: type.italic, line: type.line }
}

function textLayer(text: string, region: Rect, type: { family: string; size: number; weight: number; italic: boolean; line: number }, color: number): SceneText {
  return { text, region, family: type.family, size: type.size, weight: type.weight, italic: type.italic, line: type.line, color }
}

function musicLayer(
  track: TrackWords,
  text: string,
  region: Rect,
  type: { family: string; size: number; weight: number; italic: boolean; line: number },
  background: number,
  color: number,
  transparent: boolean,
  mode: string,
  vertical: boolean,
  copyRegion: Rect,
  playRegion: Rect,
): SceneMusic {
  return {
    title: track.title,
    artist: track.artistDisplay,
    text,
    region,
    family: type.family,
    size: type.size,
    weight: type.weight,
    italic: type.italic,
    line: type.line,
    background,
    color,
    transparentBackground: transparent,
    mode,
    vertical,
    copyRegion,
    playRegion,
  }
}

function fits(measurement: TextMeasurement, region: Rect): boolean {
  return !measurement.hasVisualOverflow && measurement.width >= 1 && measurement.width <= region.width && measurement.height >= 1 && measurement.height <= region.height
}

function inside(rect: Rect): boolean {
  return rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 && rect.x + rect.width <= CANVAS_WIDTH && rect.y + rect.height <= CANVAS_HEIGHT
}

function rectOf(value: Rect): Rect {
  return { x: value.x, y: value.y, width: value.width, height: value.height }
}

function sameSet(keys: Iterable<string>, authored: string[]): boolean {
  const left = [...keys].sort()
  const right = [...authored].sort()
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function mulDiv(value: number, scale: number, divisor: number): number {
  return Number((BigInt(value) * BigInt(scale)) / BigInt(divisor))
}

function reject(reason: string): SceneResult {
  return { kind: 'rejected', reason }
}
