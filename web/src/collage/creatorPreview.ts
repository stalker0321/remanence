import { CollageError } from './errors'
import {
  centerCoverBase,
  correctEditorCrop,
  toCropWindow,
  CROP_ASPECT_TOLERANCE,
  type CropTransform,
  type CropWindowInts,
} from './crop'
import type { PhotoShape, Rect } from './model'
import { sourceRect, type TextMeasurer } from './prepare'
import { grammar5Plans, type Grammar5Input, type Grammar5Measure, type Grammar5Plan } from './planGrammar5'
import { markLine } from './model'
import { mountRect } from './mount'
import { wrapNoteLines } from './creatorNote'
import { opaqueColor, styleById } from './styles'
import type { Scene } from './prepare'

/**
 * Creator preview adapter (pure, no DOM): grammar-5 plan + normalized
 * photos → aspect-preserving per-slot source windows + a creator
 * `Scene` for `paintScene`.
 *
 * No `ResolvedExpression` is manufactured, no grant is issued, and the
 * admitted `CollageStage` is never used: the scene is a local
 * presentation input only. Every slot keeps the destination aspect
 * after `sourceRect` rounding (existing 5e-3 tolerance) — a mismatch
 * is an explicit refusal, never a stretch. Photo ids must be unique;
 * the caller enforces it and this adapter re-checks it.
 */

export interface CreatorPhoto {
  contentId: string
  width: number
  height: number
  contentHash: string
}

export interface CreatorSlotView {
  photoId: string
  destination: Rect
  sourcePx: Rect
  crop: CropWindowInts
  shape: PhotoShape
  /** Effective transform after the composed domain clamps — what the window actually used. */
  effective: CropTransform
}

export interface CreatorNoteView {
  region: Rect
  state: string
}

export interface CreatorProvenanceView {
  kind: 'brand' | 'sender'
  text: string
  region: Rect
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
  orientation: 'horizontal' | 'vertical-up' | 'vertical-down'
  align: 'left' | 'center' | 'right'
}

export interface CreatorCandidateView {
  branch: string
  slots: CreatorSlotView[]
  note: CreatorNoteView | null
  provenance: CreatorProvenanceView[]
}

function fail(_message: string): never {
  throw new CollageError('REJECTED')
}

function uniquePhotos(photos: CreatorPhoto[]): Map<string, CreatorPhoto> {
  const byId = new Map<string, CreatorPhoto>()
  for (const photo of photos) {
    if (typeof photo.contentId !== 'string' || photo.contentId.trim() === '') fail('creator photo contentId must not be blank')
    if (
      !Number.isFinite(photo.width) ||
      !Number.isFinite(photo.height) ||
      photo.width <= 0 ||
      photo.height <= 0
    ) {
      fail(`creator photo dimensions must be finite and positive, got ${photo.contentId}`)
    }
    if (byId.has(photo.contentId)) fail(`creator photo contentId must be unique, got ${photo.contentId}`)
    byId.set(photo.contentId, photo)
  }
  return byId
}

/**
 * Aspect-preserving source window for one slot: the stored UI transform
 * is clamped into the frozen control domain FIRST, then the
 * source-bounded editor clamp plus structural/aspect checks
 * (`correctEditorCrop`). Refuses instead of stretching. Returns the
 * effective transform alongside the slot so readout and preview agree.
 */
export function previewSlot(
  tile: { photoId: string; rect: Rect; shape: string },
  photo: CreatorPhoto,
  transform: CropTransform | null,
): CreatorSlotView & { effective: CropTransform } {
  if (tile.rect.width <= 0 || tile.rect.height <= 0) fail(`creator slot has a non-positive destination for ${tile.photoId}`)
  const base = centerCoverBase(photo.width, photo.height, tile.rect.width, tile.rect.height)
  if (!base) fail(`creator slot has impossible dimensions for ${tile.photoId}`)
  const admitted = correctEditorCrop(
    base,
    { width: tile.rect.width, height: tile.rect.height },
    photo.width,
    photo.height,
    transform ?? { z: 1, xPercent: 0, yPercent: 0 },
  )
  if (!admitted.accepted) fail(`creator crop refused for ${tile.photoId}: ${admitted.reason}`)
  const crop = toCropWindow(admitted.window)
  const source = sourceRect(crop, photo.width, photo.height)
  if (!source) fail(`creator crop window degenerates for ${tile.photoId}`)
  const destinationAspect = tile.rect.width / tile.rect.height
  const sourceAspect = source.width / source.height
  if (Math.abs(sourceAspect - destinationAspect) / destinationAspect > CROP_ASPECT_TOLERANCE) {
    fail(`creator source window would stretch ${tile.photoId}`)
  }
  return {
    photoId: tile.photoId,
    destination: { x: tile.rect.x, y: tile.rect.y, width: tile.rect.width, height: tile.rect.height },
    sourcePx: source,
    crop,
    shape: tile.shape as PhotoShape,
    effective: admitted.transform,
  }
}

export interface PreviewInput {
  id: string
  sender: string
  photos: CreatorPhoto[]
  note: string | null
}

/** Effective face for the creator's rendered note (family/weight/italic). */
export interface NoteFace {
  font: string
  weight: number
  italic: boolean
}

/**
 * Adapts the injected browser `TextMeasurer` to the grammar-5 note
 * contract. Line count is derived from the measured height at the
 * requested line box (`round(height / line)`); the caller must pass a
 * measurer whose height is a whole number of line boxes for wrapped
 * text (the provided canvas measurer lays out whole lines).
 *
 * `noteFace` pins the FACE actually rendered by `creatorScene` (the
 * style note family/weight/italic), so the region is measured with the
 * same typography the note is drawn in. The plan grammar's own requested
 * face (`font`) is ignored when `noteFace` is supplied; T233's pure
 * geometry source is untouched and the size/line density still comes
 * from the grammar's short/regular rows. Without `noteFace` the grammar
 * face is used (unchanged behaviour for the golden-parity adapter tests).
 */
export function plannerFromTextMeasurer(
  measurer: TextMeasurer,
  maxWidth: number,
  maxHeight: number,
  noteFace?: NoteFace,
): Grammar5Measure {
  return (text, width, size, line, font) => {
    if (noteFace === undefined) {
      const measured = measurer.measure({ text, family: font, size, weight: 400, italic: false, line, maxWidth: Math.min(width, maxWidth), maxHeight })
      return { height: measured.height, lines: Math.max(1, Math.round(measured.height / line)) }
    }
    // Real creator note: use the SAME word-wrap the painter uses, with an
    // unwrapped line-width primitive from the injected measurer
    // (`softWrap:false` measures the widest literal line, no wrapping).
    const measureLineWidth = (lineText: string): number =>
      measurer.measure({
        text: lineText,
        family: noteFace.font,
        size,
        weight: noteFace.weight,
        italic: noteFace.italic,
        line,
        maxWidth: Number.POSITIVE_INFINITY,
        maxHeight: Number.POSITIVE_INFINITY,
        softWrap: false,
      }).width
    const wrapped = wrapNoteLines(text, Math.min(width, maxWidth), measureLineWidth)
    return { height: wrapped.lines.length * line, lines: wrapped.lines.length }
  }
}

function planInput(input: PreviewInput): Grammar5Input {
  return {
    id: input.id,
    sender: input.sender,
    photos: input.photos.map((photo) => ({ id: photo.contentId, width: photo.width, height: photo.height })),
    note: input.note,
  }
}

/**
 * Complete deterministic enumeration: one ordered view per grammar-5
 * branch, slots in plan (authored) order. `transforms` is keyed by
 * photo content id so manual crops survive reorder and branch switches.
 * `measure` overrides the T233 line-height stub with real note metrics;
 * when omitted the stub keeps unit parity exact. Throws the plan
 * layer's typed refusal for unsupported input.
 */
export function previewCandidates(
  input: PreviewInput,
  transforms?: ReadonlyMap<string, CropTransform>,
  measure?: Grammar5Measure,
): CreatorCandidateView[] {
  const byId = uniquePhotos(input.photos)
  const plans = grammar5Plans(planInput(input), undefined, measure)
  return plans.map((plan) => previewCandidate(plan, byId, transforms))
}

export function previewCandidate(
  plan: Grammar5Plan,
  byId: ReadonlyMap<string, CreatorPhoto>,
  transforms?: ReadonlyMap<string, CropTransform>,
): CreatorCandidateView {
  // T252: mount T233's fractional plan rects to canonical INT canvas units at
  // this boundary FIRST — the very same component-wise floor the Android
  // producer uses (`AllFamilyCandidateProducer.kt:245-248` /
  // `AllFamilyV4Adapter.kt:90-93,118-121`). The mounted rect is then handed to
  // `previewSlot`, so the crop/source window is derived from the exact integer
  // cell that is painted and captured, never from a fractional rect.
  const slots = plan.tiles.map((tile) => {
    const photo = byId.get(tile.photoId)
    if (!photo) fail(`creator plan needs photo ${tile.photoId}`)
    const destination = mountRect({ x: tile.rect.x, y: tile.rect.y, width: tile.rect.width, height: tile.rect.height })
    return previewSlot(
      { photoId: tile.photoId, rect: destination, shape: tile.shape },
      photo as CreatorPhoto,
      transforms?.get(tile.photoId) ?? null,
    )
  })
  const noteRegion =
    plan.note === null
      ? null
      : mountRect({ x: plan.note.rect.x, y: plan.note.rect.y, width: plan.note.rect.width, height: plan.note.rect.height })
  return {
    branch: plan.branch,
    slots,
    note:
      plan.note === null || noteRegion === null
        ? null
        : {
            region: noteRegion,
            state: plan.note.state,
          },
    provenance: plan.provenance.map((mark) => ({
      kind: mark.role === 'sender' ? ('sender' as const) : ('brand' as const),
      text: '',
      region: mountRect({ x: mark.rect.x, y: mark.rect.y, width: mark.rect.width, height: mark.rect.height }),
      family: mark.font,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.size === 64 ? 70 : markLine(mark.size),
      orientation: mark.orientation as CreatorProvenanceView['orientation'],
      align: mark.align as CreatorProvenanceView['align'],
    })),
  }
}

export interface CreatorSceneInput {
  view: CreatorCandidateView
  styleId: string
  senderHandle: string
  noteText: string | null
  photos: ReadonlyMap<string, { width: number; height: number }>
}

/**
 * Local presentation scene for `paintScene` — not a sealed expression.
 * Provenance text is bound here (`Remanence` / `From <handle>`); note
 * typography mirrors the adapter's `textType` (`line = ceil(size*1.12)`)
 * while measurement/fit stays with the injected browser measurer.
 */
export function creatorScene(input: CreatorSceneInput): Scene {
  const style = styleById(input.styleId)
  if (!style) fail(`creator style is not bundled: ${input.styleId}`)
  if (input.senderHandle.trim() === '') fail('creator sender handle must not be blank')
  const photos = input.view.slots.map((slot) => {
    const meta = input.photos.get(slot.photoId)
    if (!meta || meta.width <= 0 || meta.height <= 0) fail(`creator is missing source dimensions for ${slot.photoId}`)
    return {
      contentId: slot.photoId,
      destination: { ...slot.destination },
      source: { ...slot.sourcePx },
      shape: slot.shape,
    }
  })
  // The plan's note region is sized by `grammar5NoteState` with the
  // grammar typography (StudySerif 27/30 short, 16/20 regular). Render
  // the creator note at the SAME state size/line so the real measured
  // region actually contains it; using the style's nominal size (e.g.
  // StudySans 22) over an 16/20-sized region overflowed and refused every
  // multi-line note. The style supplies the face/palette; the plan owns
  // the density that its region was measured for.
  const noteState = input.view.note?.state
  const noteSize = noteState === 'regular' ? 16 : 27
  const noteLine = noteState === 'regular' ? 20 : 30
  const note =
    input.noteText !== null && input.view.note !== null
      ? {
          text: input.noteText,
          region: { ...input.view.note.region },
          family: style.note.font,
          size: noteSize,
          weight: style.note.weight,
          italic: style.note.italic,
          line: noteLine,
          color: opaqueColor(style.palette.ink),
        }
      : null
  return {
    canvasWidth: 360,
    canvasHeight: 640,
    styleId: style.id,
    grammarId: 'g5',
    paper: opaqueColor(style.palette.paper),
    ink: opaqueColor(style.palette.ink),
    sound: opaqueColor(style.palette.sound),
    soundInk: opaqueColor(style.palette.soundInk),
    photos,
    note,
    music: null,
    artwork: null,
    provenance: input.view.provenance.map((mark) => ({
      kind: mark.kind,
      text: mark.kind === 'brand' ? 'Remanence' : `From ${input.senderHandle}`,
      region: { ...mark.region },
      family: mark.family,
      size: mark.size,
      weight: mark.weight,
      italic: mark.italic,
      line: mark.line,
      orientation: mark.orientation,
      align: mark.align,
      color: opaqueColor(style.palette.ink),
      tracking: mark.kind === 'brand' ? style.tracking : 0,
    })),
    decor: [],
  }
}
