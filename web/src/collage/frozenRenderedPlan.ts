import { CollageError } from './errors'
import type { CreatorNoteLayout } from './creatorNote'
import type { PhotoShape, Rect } from './model'
import type { Scene } from './prepare'
import { FONT_VERSION } from './fonts'
import { PALETTE_VERSION } from './styles'

/**
 * T247 — the exact, successfully painted local creator render captured as
 * deeply immutable plain data.
 *
 * This is a local presentation snapshot, NOT a sealed expression, grant,
 * publication, or server action. It holds only numbers/strings copied out of
 * the painted `Scene` + the canonical note layout — no `ImageBitmap`, canvas,
 * DOM node, map capability, photo bytes, keys or URLs. Every nested array and
 * object is deep-copied and frozen, so later mutation of the source scene/view
 * or of the live inputs can never change a captured plan. The later sender
 * consumes this frozen data; it must NEVER regenerate the plan from changed
 * live inputs.
 */

export interface FrozenRenderedRect {
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

export interface FrozenRenderedSlot {
  readonly contentId: string
  readonly ordinal: number
  /** Plan tile destination cell, in canvas units. */
  readonly destination: FrozenRenderedRect
  /** Source pixel rect drawn from the source bitmap. */
  readonly source: FrozenRenderedRect
  readonly shape: PhotoShape
}

export interface FrozenRenderedNoteRow {
  readonly text: string
  /** Painted x (the note region's left edge). */
  readonly x: number
  /** Painted baseline within the canvas. */
  readonly baseline: number
  readonly width: number
}

export interface FrozenRenderedNote {
  readonly text: string
  readonly region: FrozenRenderedRect
  readonly family: string
  readonly size: number
  readonly weight: number
  readonly italic: boolean
  readonly line: number
  readonly color: number
  /** Actual wrapped rows painted, in order (empty rows advance a baseline). */
  readonly rows: readonly FrozenRenderedNoteRow[]
}

export interface FrozenRenderedProvenance {
  readonly kind: 'brand' | 'sender'
  readonly text: string
  readonly region: FrozenRenderedRect
  readonly family: string
  readonly size: number
  readonly weight: number
  readonly italic: boolean
  readonly line: number
  readonly orientation: 'horizontal' | 'vertical-up' | 'vertical-down'
  readonly align: 'left' | 'center' | 'right'
  readonly tracking: number
  readonly color: number
}

export interface FrozenRenderedDecor {
  readonly kind: 'carrier' | 'acid-ribbon' | 'print-plane'
  readonly region: FrozenRenderedRect
  readonly background: number
  readonly border: number | null
  readonly borderWidth: number
}

export interface FrozenRenderedPlan {
  readonly canvasVersion: number
  readonly grammarId: 'g5'
  readonly branchId: string
  readonly canvas: {
    readonly width: number
    readonly height: number
    readonly paper: number
    readonly ink: number
    readonly sound: number
    readonly soundInk: number
  }
  readonly style: { readonly styleId: string; readonly styleVersion: string }
  readonly fontVersion: number
  readonly paletteVersion: number
  readonly slots: readonly FrozenRenderedSlot[]
  readonly note: FrozenRenderedNote | null
  readonly provenance: readonly FrozenRenderedProvenance[]
  readonly decor: readonly FrozenRenderedDecor[]
}

export interface FrozenRenderedCaptureInput {
  /** The exact `Scene` that `paintScene` successfully painted. */
  scene: Scene
  /** The canonical wrapped note layout that was painted on top (null iff no note). */
  noteLayout: CreatorNoteLayout | null
  branchId: string
  styleId: string
  styleVersion: string
}

function rect(value: Rect): FrozenRenderedRect {
  return { x: value.x, y: value.y, width: value.width, height: value.height }
}

/** Recursively freeze a freshly constructed plain-data tree (arrays + objects). */
function deepFreeze<T>(value: T): T {
  if (Array.isArray(value)) {
    for (const item of value) deepFreeze(item)
    return Object.freeze(value) as unknown as T
  }
  if (value !== null && typeof value === 'object') {
    for (const key of Object.keys(value as Record<string, unknown>)) {
      deepFreeze((value as Record<string, unknown>)[key])
    }
    return Object.freeze(value) as T
  }
  return value
}

/**
 * Deep-copy + deep-freeze the exact painted render. Throws `REJECTED` when the
 * required rendered data is missing (a note with no wrapped-row layout) so the
 * caller fails closed instead of recording a note it did not actually paint.
 */
export function captureFrozenRenderedPlan(input: FrozenRenderedCaptureInput): FrozenRenderedPlan {
  const { scene } = input
  if (scene.grammarId !== 'g5') throw new CollageError('REJECTED')
  if (input.branchId.trim() === '' || input.styleId.trim() === '') throw new CollageError('REJECTED')
  if (scene.note !== null && input.noteLayout === null) throw new CollageError('REJECTED')
  const slots: FrozenRenderedSlot[] = scene.photos.map((photo, index) => ({
    contentId: photo.contentId,
    ordinal: index,
    destination: rect(photo.destination),
    source: rect(photo.source),
    shape: photo.shape,
  }))
  const note: FrozenRenderedNote | null =
    scene.note === null
      ? null
      : {
          text: scene.note.text,
          region: rect(scene.note.region),
          family: scene.note.family,
          size: scene.note.size,
          weight: scene.note.weight,
          italic: scene.note.italic,
          line: scene.note.line,
          color: scene.note.color,
          rows: input.noteLayout!.lines.map((row) => ({
            text: row.text,
            x: scene.note!.region.x,
            baseline: row.baseline,
            width: row.width,
          })),
        }
  const provenance: FrozenRenderedProvenance[] = scene.provenance.map((mark) => ({
    kind: mark.kind,
    text: mark.text,
    region: rect(mark.region),
    family: mark.family,
    size: mark.size,
    weight: mark.weight,
    italic: mark.italic,
    line: mark.line,
    orientation: mark.orientation,
    align: mark.align,
    tracking: mark.tracking,
    color: mark.color,
  }))
  const decor: FrozenRenderedDecor[] = scene.decor.map((plane) => ({
    kind: plane.kind,
    region: rect(plane.region),
    background: plane.background,
    border: plane.border,
    borderWidth: plane.borderWidth,
  }))
  const plan: FrozenRenderedPlan = {
    canvasVersion: 1,
    grammarId: 'g5',
    branchId: input.branchId,
    canvas: {
      width: scene.canvasWidth,
      height: scene.canvasHeight,
      paper: scene.paper,
      ink: scene.ink,
      sound: scene.sound,
      soundInk: scene.soundInk,
    },
    style: { styleId: input.styleId, styleVersion: input.styleVersion },
    fontVersion: FONT_VERSION,
    paletteVersion: PALETTE_VERSION,
    slots,
    note,
    provenance,
    decor,
  }
  return deepFreeze(plan)
}
