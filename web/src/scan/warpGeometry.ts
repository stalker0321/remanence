/**
 * T316 — pure perspective-warp geometry for the genuine sender path. Mirrors the
 * accepted Android `PerspectiveWarper` dimension/orientation policy: opposite-
 * edge averages choose orientation, portrait quads rotate into canonical
 * landscape, long edge maps to 1600, short edge is ties-even rounded and
 * clamped 1..4000, 16M pixel budget. Native `getPerspectiveTransform` /
 * `warpPerspective` run in the bridge; this module owns the math.
 */

import { rint } from './siftExtractor'
import { validateQuad, type Point2D } from './cornerGeometry'

export const WARP_LONG_EDGE_PX = 1600
export const WARP_MAX_SHORT_EDGE_PX = 4000
export const WARP_MAX_PIXELS = 16_000_000

export interface WarpPlan {
  /** Source corners in canonical (landscape) order. */
  readonly source: readonly [Point2D, Point2D, Point2D, Point2D]
  readonly targetWidth: number
  readonly targetHeight: number
  readonly landscape: boolean
}

function edgeLength(a: Point2D, b: Point2D): number {
  return Math.hypot(b.x - a.x, b.y - a.y)
}

/** Destination corners for a target frame (uses width-1/height-1 like Android). */
export function targetCorners(targetWidth: number, targetHeight: number): [Point2D, Point2D, Point2D, Point2D] {
  return [
    { x: 0, y: 0 },
    { x: targetWidth - 1, y: 0 },
    { x: targetWidth - 1, y: targetHeight - 1 },
    { x: 0, y: targetHeight - 1 },
  ]
}

/**
 * Plans the warp for already-ordered quad corners. Throws on invalid quads
 * (caller maps to a typed `warp` refusal); portrait quads rotate [1,2,3,0].
 */
export function planWarp(ordered: readonly [Point2D, Point2D, Point2D, Point2D]): WarpPlan {
  const validation = validateQuad(ordered)
  if (validation.valid !== true) throw new Error(`invalid quad: ${validation.reason}`)
  const edges = [0, 1, 2, 3].map((i) => edgeLength(ordered[i]!, ordered[(i + 1) % 4]!))
  const horizontal = (edges[0]! + edges[2]!) / 2
  const vertical = (edges[1]! + edges[3]!) / 2
  if (!Number.isFinite(horizontal) || !Number.isFinite(vertical) || horizontal <= 0 || vertical <= 0) {
    throw new Error('degenerate quad edges')
  }
  const landscape = horizontal >= vertical
  const source = (landscape ? ordered : [ordered[1], ordered[2], ordered[3], ordered[0]]) as readonly [
    Point2D,
    Point2D,
    Point2D,
    Point2D,
  ]
  const longEdge = Math.max(horizontal, vertical)
  const shortEdge = Math.min(horizontal, vertical)
  const scale = WARP_LONG_EDGE_PX / longEdge
  const targetWidth = WARP_LONG_EDGE_PX
  const targetHeight = Math.min(WARP_MAX_SHORT_EDGE_PX, Math.max(1, rint(shortEdge * scale)))
  if (targetWidth * targetHeight > WARP_MAX_PIXELS) throw new Error('warped output exceeds pixel budget')
  return { source, targetWidth, targetHeight, landscape }
}
