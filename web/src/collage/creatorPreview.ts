import { CollageError } from './errors'
import {
  centerCoverBase,
  clampEditorCrop,
  correctCrop,
  sourceWindow,
  toCropWindow,
  CROP_ASPECT_TOLERANCE,
  type CropTransform,
  type CropWindowInts,
} from './crop'
import type { PhotoShape, Rect } from './model'
import { sourceRect } from './prepare'
import { grammar5Plans, type Grammar5Input, type Grammar5Plan } from './planGrammar5'
import { markLine } from './model'
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
 * Aspect-preserving source window for one slot: center-cover base,
 * source-bounded manual transform, sealed quantization, then the
 * destination-vs-rounded-source aspect gate. Refuses instead of
 * stretching.
 */
export function previewSlot(
  tile: { photoId: string; rect: Rect; shape: string },
  photo: CreatorPhoto,
  transform: CropTransform | null,
): CreatorSlotView {
  if (tile.rect.width <= 0 || tile.rect.height <= 0) fail(`creator slot has a non-positive destination for ${tile.photoId}`)
  const base = centerCoverBase(photo.width, photo.height, tile.rect.width, tile.rect.height)
  if (!base) fail(`creator slot has impossible dimensions for ${tile.photoId}`)
  const clamped = clampEditorCrop(base, transform ?? { z: 1, xPercent: 0, yPercent: 0 })
  if (!clamped) fail(`creator crop is outside its source for ${tile.photoId}`)
  const window = sourceWindow(base, clamped)
  if (!window) fail(`creator crop inversion failed for ${tile.photoId}`)
  const admitted = correctCrop(base, { width: tile.rect.width, height: tile.rect.height }, photo.width, photo.height, clamped)
  if (!admitted.accepted) fail(`creator crop refused for ${tile.photoId}: ${admitted.reason}`)
  const crop = toCropWindow(window)
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
  }
}

export interface PreviewInput {
  id: string
  sender: string
  photos: CreatorPhoto[]
  note: string | null
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
 * Throws the plan layer's typed refusal for unsupported input.
 */
export function previewCandidates(
  input: PreviewInput,
  transforms?: ReadonlyMap<string, CropTransform>,
): CreatorCandidateView[] {
  const byId = uniquePhotos(input.photos)
  const plans = grammar5Plans(planInput(input))
  return plans.map((plan) => previewCandidate(plan, byId, transforms))
}

export function previewCandidate(
  plan: Grammar5Plan,
  byId: ReadonlyMap<string, CreatorPhoto>,
  transforms?: ReadonlyMap<string, CropTransform>,
): CreatorCandidateView {
  const slots = plan.tiles.map((tile) => {
    const photo = byId.get(tile.photoId)
    if (!photo) fail(`creator plan needs photo ${tile.photoId}`)
    return previewSlot(
      { photoId: tile.photoId, rect: { x: tile.rect.x, y: tile.rect.y, width: tile.rect.width, height: tile.rect.height }, shape: tile.shape },
      photo as CreatorPhoto,
      transforms?.get(tile.photoId) ?? null,
    )
  })
  return {
    branch: plan.branch,
    slots,
    note:
      plan.note === null
        ? null
        : {
            region: { x: plan.note.rect.x, y: plan.note.rect.y, width: plan.note.rect.width, height: plan.note.rect.height },
            state: plan.note.state,
          },
    provenance: plan.provenance.map((mark) => ({
      kind: mark.role === 'sender' ? ('sender' as const) : ('brand' as const),
      text: '',
      region: { x: mark.rect.x, y: mark.rect.y, width: mark.rect.width, height: mark.rect.height },
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
  const note =
    input.noteText !== null && input.view.note !== null
      ? {
          text: input.noteText,
          region: { ...input.view.note.region },
          family: style.note.font,
          size: style.note.size,
          weight: style.note.weight,
          italic: style.note.italic,
          line: Math.ceil(style.note.size * 1.12),
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
