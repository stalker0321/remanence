/**
 * Creator-only fixed-frame crop math — faithful port of the Android
 * `FixedFrameCropCorrection` transform domain plus the source-bounded
 * editor (`CropEditorGeometry`) and the sealed `toCropWindow`
 * quantization, minus the analysis/classifier half.
 *
 * Two domains, kept distinct on purpose (lead-adjudicated local
 * control-domain choice — not universal Android editor parity):
 *
 * - `correctCrop` ports Android `correct` steps 1–8 verbatim, including
 *   the frozen `|pan| <= (z-1)*50` gate and z=1 ⇒ pan 0.
 * - `correctEditorCrop` composes the STORED manual-control domain used
 *   by this creator: `clampTransform` first (frozen z∈[1,1.25] domain,
 *   pan into `(z-1)*50`, z=1 ⇒ exactly 0, non-finite refused), then the
 *   source-bounded editor clamp plus the structural checks (base in
 *   source, 5e-3 frame aspect, derived window in [0,1]^2). The frozen pan
 *   gate is enforced on the stored input, never re-run on the editor
 *   output — matching Android's editor→quantize path, which does not
 *   re-run the automatic classifier. A pan press at z=1 is therefore a
 *   safe no-op and can never empty the candidate list.
 *
 * The frame (slot cell) is fixed; only the source moves beneath it. UI
 * state is `translate(xPercent%, yPercent%) scale(z)` on the source
 * fragment inside the frame, with `z in [1, 1.25]` and
 * `|x|,|y| <= (z - 1) * 50` percent of the frame box. At `z = 1` both
 * pan axes are exactly `0` (never `-0`).
 *
 * Creator-only starting base (the ONE approved local decision, not
 * Android analysis parity): center-cover of the source over the cell —
 * frame aspect equals cell aspect, centered, inside `[0,1]^2`, and
 * `max(w,h) = 1.0`, so the editor clamp reduces to `z in [1,1.25]`.
 * Never a full-source stretch, never fabricated face/importance
 * analysis, never an envelope-50 parity claim.
 */

export interface CropTransform {
  z: number
  xPercent: number
  yPercent: number
}

export type CropPanDirection = 'left' | 'up' | 'down' | 'right'

export interface CropPanDelta {
  xPercent: number
  yPercent: number
}

export interface NormalizedCrop {
  x: number
  y: number
  width: number
  height: number
}

export interface CropSlot {
  width: number
  height: number
}

export interface CropWindowInts {
  x: number
  y: number
  width: number
  height: number
}

export type CropGeometryReject =
  | 'NONFINITE_VALUE'
  | 'ZOOM_OUT_OF_DOMAIN'
  | 'PAN_BEYOND_LIMIT'
  | 'INVALID_SOURCE_DIMENSIONS'
  | 'INVALID_SLOT'
  | 'BASE_CROP_OUT_OF_SOURCE'
  | 'FRAME_ASPECT_MISMATCH'
  | 'WINDOW_OUT_OF_SOURCE'

export type CropOutcome =
  | { accepted: true; window: NormalizedCrop }
  | { accepted: false; reason: CropGeometryReject }

export const CROP_ZOOM_MIN = 1
export const CROP_ZOOM_MAX = 1.25
export const CROP_ZOOM_STEP = 0.01
export const CROP_PAN_LIMIT_PERCENT_PER_UNIT_ZOOM = 50
/** Relative tolerance for "the approved crop has the frame's aspect". */
export const CROP_ASPECT_TOLERANCE = 5e-3
/** Sealed 0..10000 crop unit max (matches `CROP_MAX_V3`). */
export const CROP_UNIT_MAX = 10_000

const EPSILON = 1e-9
const SOURCE_EDGE_SLACK = 1.000001

/** `limit = (z - 1) x 50` percent. NaN in, NaN out. */
export function panLimitPercent(z: number): number {
  return (z - 1) * CROP_PAN_LIMIT_PERCENT_PER_UNIT_ZOOM
}

/**
 * Exact `clampCrop` parity: clamp `z` to `[1, 1.25]` first, then clamp
 * both pan axes to `(z - 1) x 50`. At `z = 1` the limit is 0, so both
 * axes clamp to exactly `0` (never `-0`). `null` for non-finite input.
 */
export function clampTransform(z: number, xPercent: number, yPercent: number): CropTransform | null {
  if (!Number.isFinite(z) || !Number.isFinite(xPercent) || !Number.isFinite(yPercent)) return null
  const zoom = Math.min(CROP_ZOOM_MAX, Math.max(CROP_ZOOM_MIN, z))
  const limit = panLimitPercent(zoom)
  if (limit <= 0) return { z: zoom, xPercent: 0, yPercent: 0 }
  return {
    z: zoom,
    xPercent: Math.min(limit, Math.max(-limit, xPercent)),
    yPercent: Math.min(limit, Math.max(-limit, yPercent)),
  }
}

/** Direction-button/key press delta: exactly one axis, +-1 frame percent. */
export function panDelta(direction: CropPanDirection): CropPanDelta {
  if (direction === 'left') return { xPercent: -1, yPercent: 0 }
  if (direction === 'right') return { xPercent: 1, yPercent: 0 }
  if (direction === 'up') return { xPercent: 0, yPercent: -1 }
  return { xPercent: 0, yPercent: 1 }
}

function isFiniteCrop(crop: NormalizedCrop): boolean {
  return Number.isFinite(crop.x) && Number.isFinite(crop.y) && Number.isFinite(crop.width) && Number.isFinite(crop.height)
}

function isFiniteTransform(transform: CropTransform): boolean {
  return Number.isFinite(transform.z) && Number.isFinite(transform.xPercent) && Number.isFinite(transform.yPercent)
}

/**
 * Pure inversion of the stage transform: the source window the frame
 * shows for `transform` over `baseCrop`.
 *
 * `left = bx + bw x (0.5 - (0.5 + x/100) / z)`, `width = bw / z` (and
 * likewise on y). Identity (`z=1, x=y=0`) returns `baseCrop` exactly.
 * `null` for non-finite input or a non-positive zoom.
 */
export function sourceWindow(baseCrop: NormalizedCrop, transform: CropTransform): NormalizedCrop | null {
  if (!isFiniteCrop(baseCrop) || !isFiniteTransform(transform)) return null
  if (transform.z <= 0) return null
  const inverse = 1 / transform.z
  if (!Number.isFinite(inverse)) return null
  const left = baseCrop.x + baseCrop.width * (0.5 - ((0.5 + transform.xPercent / 100) * inverse))
  const top = baseCrop.y + baseCrop.height * (0.5 - ((0.5 + transform.yPercent / 100) * inverse))
  const width = baseCrop.width * inverse
  const height = baseCrop.height * inverse
  const window = { x: left, y: top, width, height }
  if (!isFiniteCrop(window)) return null
  return window
}

/**
 * Creator-only starting base: center-cover of the source over the cell.
 * Frame aspect equals cell aspect, centered, inside `[0,1]^2`, and
 * `max(w,h) = 1.0`. `null` for non-finite or non-positive dimensions.
 */
export function centerCoverBase(
  sourceWidth: number,
  sourceHeight: number,
  cellWidth: number,
  cellHeight: number,
): NormalizedCrop | null {
  for (const value of [sourceWidth, sourceHeight, cellWidth, cellHeight]) {
    if (!Number.isFinite(value) || value <= 0) return null
  }
  const scale = Math.max(cellWidth / sourceWidth, cellHeight / sourceHeight)
  if (!Number.isFinite(scale) || scale <= 0) return null
  const width = cellWidth / (sourceWidth * scale)
  const height = cellHeight / (sourceHeight * scale)
  const window = { x: (1 - width) / 2, y: (1 - height) / 2, width, height }
  if (!isFiniteCrop(window) || width <= 0 || height <= 0) return null
  return window
}

function validEditorBase(base: NormalizedCrop): boolean {
  return (
    isFiniteCrop(base) &&
    base.width > 0 &&
    base.height > 0 &&
    base.x >= 0 &&
    base.y >= 0 &&
    base.x + base.width <= SOURCE_EDGE_SLACK &&
    base.y + base.height <= SOURCE_EDGE_SLACK
  )
}

/**
 * Source-bounded editor clamp (`CropEditorGeometry.clamp` parity).
 * Zooming out recovers the source around the initial crop; the frame
 * keeps its slot aspect and never crosses the source edge. The zoom
 * floor is `max(base.w, base.h)` (which is `1` for a center-cover
 * base), the ceiling is `1.25`. `null` for invalid input.
 */
export function clampEditorCrop(base: NormalizedCrop, transform: CropTransform): CropTransform | null {
  if (!validEditorBase(base) || !isFiniteTransform(transform)) return null
  const zoom = Math.min(CROP_ZOOM_MAX, Math.max(Math.max(base.width, base.height), transform.z))
  const width = base.width / zoom
  const height = base.height / zoom
  const centerX = Math.min(1 - width / 2, Math.max(width / 2, base.x + base.width / 2 - (transform.xPercent * width) / 100))
  const centerY = Math.min(1 - height / 2, Math.max(height / 2, base.y + base.height / 2 - (transform.yPercent * height) / 100))
  return {
    z: zoom,
    xPercent: ((base.x + base.width / 2 - centerX) * 100) / width,
    yPercent: ((base.y + base.height / 2 - centerY) * 100) / height,
  }
}

/** Editor window: source-bounded clamp, then the fixed-frame inversion. */
export function editorWindow(base: NormalizedCrop, transform: CropTransform): NormalizedCrop | null {
  const clamped = clampEditorCrop(base, transform)
  if (!clamped) return null
  return sourceWindow(base, clamped)
}

/**
 * Drag deltas and pinch centroid are fractions of the fitted source,
 * excluding letterboxing. Positive drag moves the FRAME in the same
 * direction as the finger. Pinching out narrows the retained area around
 * the pinch centroid. `null` for invalid input (including a
 * non-positive zoom factor).
 */
export function editorGesture(
  base: NormalizedCrop,
  transform: CropTransform,
  dragX: number,
  dragY: number,
  zoomFactor: number,
  centroidX: number,
  centroidY: number,
): CropTransform | null {
  if (
    !Number.isFinite(dragX) ||
    !Number.isFinite(dragY) ||
    !Number.isFinite(zoomFactor) ||
    zoomFactor <= 0 ||
    !Number.isFinite(centroidX) ||
    !Number.isFinite(centroidY)
  ) {
    return null
  }
  const bounded = clampEditorCrop(base, transform)
  if (!bounded) return null
  const before = editorWindow(base, bounded)
  if (!before) return null
  const zoom = Math.min(CROP_ZOOM_MAX, Math.max(Math.max(base.width, base.height), bounded.z * zoomFactor))
  const scale = bounded.z / zoom
  const width = base.width / zoom
  const height = base.height / zoom
  const left = centroidX + (before.x - centroidX) * scale + dragX
  const top = centroidY + (before.y - centroidY) * scale + dragY
  return clampEditorCrop(base, {
    z: zoom,
    xPercent: ((base.x + base.width / 2 - left - width / 2) * 100) / width,
    yPercent: ((base.y + base.height / 2 - top - height / 2) * 100) / height,
  })
}

/**
 * Quantize a normalized crop to sealed 0..10000 integers, clamping the
 * right/bottom edge so `x + width <= 10000` (and `y + height`).
 */
export function toCropWindow(crop: NormalizedCrop): CropWindowInts {
  const quantize = (value: number): number => Math.min(CROP_UNIT_MAX, Math.max(0, Math.round(value * CROP_UNIT_MAX)))
  const left = quantize(crop.x)
  const top = quantize(crop.y)
  let right = quantize(crop.width)
  let bottom = quantize(crop.height)
  if (left + right > CROP_UNIT_MAX) right = CROP_UNIT_MAX - left
  if (top + bottom > CROP_UNIT_MAX) bottom = CROP_UNIT_MAX - top
  return { x: left, y: top, width: right, height: bottom }
}

/**
 * Geometry gates only (Android `correct` steps 1–8): the derived source
 * window is admitted only when every precondition holds, in fixed order.
 * Pan beyond the limit is rejected, never silently re-clamped — clamp
 * with `clampTransform` first. No analysis, no frozen classifier: a
 * local freeze is not protocol admission.
 */
export function correctCrop(
  baseCrop: NormalizedCrop,
  slot: CropSlot,
  sourceWidth: number,
  sourceHeight: number,
  transform: CropTransform,
): CropOutcome {
  const reject = (reason: CropOutcome & { accepted: false }): CropOutcome => reason
  if (!isFiniteCrop(baseCrop) || !isFiniteTransform(transform)) {
    return reject({ accepted: false, reason: 'NONFINITE_VALUE' })
  }
  if (transform.z < CROP_ZOOM_MIN || transform.z > CROP_ZOOM_MAX) {
    return reject({ accepted: false, reason: 'ZOOM_OUT_OF_DOMAIN' })
  }
  const limit = panLimitPercent(transform.z)
  if (Math.abs(transform.xPercent) > limit + EPSILON || Math.abs(transform.yPercent) > limit + EPSILON) {
    return reject({ accepted: false, reason: 'PAN_BEYOND_LIMIT' })
  }
  if (!Number.isFinite(sourceWidth) || !Number.isFinite(sourceHeight) || sourceWidth <= 0 || sourceHeight <= 0) {
    return reject({ accepted: false, reason: 'INVALID_SOURCE_DIMENSIONS' })
  }
  if (!Number.isFinite(slot.width) || !Number.isFinite(slot.height) || slot.width <= 0 || slot.height <= 0) {
    return reject({ accepted: false, reason: 'INVALID_SLOT' })
  }
  if (
    baseCrop.width <= 0 ||
    baseCrop.height <= 0 ||
    baseCrop.x < -EPSILON ||
    baseCrop.y < -EPSILON ||
    baseCrop.x + baseCrop.width > 1 + EPSILON ||
    baseCrop.y + baseCrop.height > 1 + EPSILON
  ) {
    return reject({ accepted: false, reason: 'BASE_CROP_OUT_OF_SOURCE' })
  }
  const slotAspect = slot.width / slot.height
  const cropAspect = (baseCrop.width * sourceWidth) / (baseCrop.height * sourceHeight)
  if (Math.abs(cropAspect - slotAspect) / slotAspect > CROP_ASPECT_TOLERANCE) {
    return reject({ accepted: false, reason: 'FRAME_ASPECT_MISMATCH' })
  }
  const window = sourceWindow(baseCrop, transform)
  if (!window) return reject({ accepted: false, reason: 'NONFINITE_VALUE' })
  if (
    window.width <= 0 ||
    window.height <= 0 ||
    window.x < -EPSILON ||
    window.y < -EPSILON ||
    window.x + window.width > 1 + EPSILON ||
    window.y + window.height > 1 + EPSILON
  ) {
    return reject({ accepted: false, reason: 'WINDOW_OUT_OF_SOURCE' })
  }
  return { accepted: true, window }
}

/**
 * Stored manual-control correction for this creator: the stored UI
 * transform is clamped into the frozen domain FIRST
 * (`clampTransform`: z∈[1,1.25], pan into `(z−1)*50`, z=1 ⇒ exactly 0,
 * non-finite refused), then the source-bounded editor clamp plus the
 * structural checks (base in source, 5e-3 frame aspect, derived window
 * in `[0,1]^2`). The frozen pan gate is NOT re-run on the editor
 * output. Returns the admitted window together with the effective
 * transform, so readout, stored state, and preview can never diverge.
 */
export function correctEditorCrop(
  baseCrop: NormalizedCrop,
  slot: CropSlot,
  sourceWidth: number,
  sourceHeight: number,
  transform: CropTransform,
): { accepted: true; window: NormalizedCrop; transform: CropTransform } | { accepted: false; reason: CropGeometryReject } {
  const reject = (reason: CropGeometryReject): { accepted: false; reason: CropGeometryReject } => ({ accepted: false, reason })
  if (!isFiniteCrop(baseCrop) || !isFiniteTransform(transform)) {
    return reject('NONFINITE_VALUE')
  }
  const frozen = clampTransform(transform.z, transform.xPercent, transform.yPercent)
  if (!frozen) return reject('NONFINITE_VALUE')
  if (!Number.isFinite(sourceWidth) || !Number.isFinite(sourceHeight) || sourceWidth <= 0 || sourceHeight <= 0) {
    return reject('INVALID_SOURCE_DIMENSIONS')
  }
  if (!Number.isFinite(slot.width) || !Number.isFinite(slot.height) || slot.width <= 0 || slot.height <= 0) {
    return reject('INVALID_SLOT')
  }
  if (
    baseCrop.width <= 0 ||
    baseCrop.height <= 0 ||
    baseCrop.x < -EPSILON ||
    baseCrop.y < -EPSILON ||
    baseCrop.x + baseCrop.width > 1 + EPSILON ||
    baseCrop.y + baseCrop.height > 1 + EPSILON
  ) {
    return reject('BASE_CROP_OUT_OF_SOURCE')
  }
  const slotAspect = slot.width / slot.height
  const cropAspect = (baseCrop.width * sourceWidth) / (baseCrop.height * sourceHeight)
  if (Math.abs(cropAspect - slotAspect) / slotAspect > CROP_ASPECT_TOLERANCE) {
    return reject('FRAME_ASPECT_MISMATCH')
  }
  const clamped = clampEditorCrop(baseCrop, frozen)
  if (!clamped) return reject('BASE_CROP_OUT_OF_SOURCE')
  const window = sourceWindow(baseCrop, clamped)
  if (!window) return reject('NONFINITE_VALUE')
  if (
    window.width <= 0 ||
    window.height <= 0 ||
    window.x < -EPSILON ||
    window.y < -EPSILON ||
    window.x + window.width > 1 + EPSILON ||
    window.y + window.height > 1 + EPSILON
  ) {
    return reject('WINDOW_OUT_OF_SOURCE')
  }
  return { accepted: true, window, transform: clamped }
}
