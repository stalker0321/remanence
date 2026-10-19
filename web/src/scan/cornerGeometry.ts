/**
 * T316 — pure four-corner ordering/validation math for the genuine sender
 * localization path. Mirrors the accepted Android `CornerGeometry`
 * (order clockwise from top-left, convex, simple, positive clockwise area).
 * No native calls, no allocation beyond small arrays; all predicates pure.
 */

export interface Point2D {
  readonly x: number
  readonly y: number
}

export const CORNER_EPSILON = 1e-9

export type QuadInvalidReason =
  | 'WRONG_POINT_COUNT'
  | 'DUPLICATE_CORNERS'
  | 'DEGENERATE_AREA'
  | 'NOT_CONVEX'
  | 'SELF_INTERSECTING'
  | 'WRONG_WINDING'

export type QuadValidation = { valid: true; ordered: Point2D[] } | { valid: false; reason: QuadInvalidReason }

/**
 * Canonicalizes four distinct corners into clockwise-from-top-left order.
 * Throws on wrong count or duplicate points (caller maps to a typed refusal).
 */
export function orderClockwiseFromTopLeft(corners: readonly Point2D[], epsilon = CORNER_EPSILON): Point2D[] {
  if (corners.length !== 4) throw new Error('exactly four corners required')
  const unique = new Set(corners.map((p) => `${p.x},${p.y}`))
  if (unique.size !== 4) throw new Error('duplicate corners')
  const centroidX = (corners[0]!.x + corners[1]!.x + corners[2]!.x + corners[3]!.x) / 4
  const centroidY = (corners[0]!.y + corners[1]!.y + corners[2]!.y + corners[3]!.y) / 4
  const angle = (p: Point2D): number => {
    const raw = Math.atan2(p.y - centroidY, p.x - centroidX) % (2 * Math.PI)
    return raw < 0 ? raw + 2 * Math.PI : raw
  }
  const sorted = [...corners].sort((a, b) => angle(a) - angle(b))
  let start = 0
  let best = sorted[0]!.x + sorted[0]!.y
  for (let i = 1; i < 4; i++) {
    const key = sorted[i]!.x + sorted[i]!.y
    if (key < best) {
      best = key
      start = i
    }
  }
  void epsilon
  return [0, 1, 2, 3].map((k) => sorted[(start + k) % 4]!)
}

/** Shoelace signed area; positive means clockwise in image coordinates. */
export function signedArea(polygon: readonly Point2D[]): number {
  if (polygon.length < 3) return 0
  let sum = 0
  for (let i = 0; i < polygon.length; i++) {
    const a = polygon[i]!
    const b = polygon[(i + 1) % polygon.length]!
    sum += a.x * b.y - b.x * a.y
  }
  return sum / 2
}

/** Convexity via cross-product sign consistency over consecutive edges. */
export function isConvex(polygon: readonly Point2D[], epsilon = CORNER_EPSILON): boolean {
  if (polygon.length !== 4) return false
  let sign = 0
  for (let i = 0; i < 4; i++) {
    const o = polygon[i]!
    const a = polygon[(i + 1) % 4]!
    const b = polygon[(i + 2) % 4]!
    const cross = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    if (cross > epsilon) {
      if (sign < 0) return false
      sign = 1
    } else if (cross < -epsilon) {
      if (sign > 0) return false
      sign = -1
    } else {
      return false
    }
  }
  return true
}

/** True when any two non-adjacent segments of the ordered polygon cross. */
export function selfIntersects(polygon: readonly Point2D[], epsilon = CORNER_EPSILON): boolean {
  if (polygon.length !== 4) return false
  const direction = (a: Point2D, b: Point2D, c: Point2D): number =>
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
  const segmentsCross = (p1: Point2D, p2: Point2D, p3: Point2D, p4: Point2D): boolean => {
    const d1 = direction(p3, p4, p1)
    const d2 = direction(p3, p4, p2)
    const d3 = direction(p1, p2, p3)
    const d4 = direction(p1, p2, p4)
    return (
      ((d1 > epsilon && d2 < -epsilon) || (d1 < -epsilon && d2 > epsilon)) &&
      ((d3 > epsilon && d4 < -epsilon) || (d3 < -epsilon && d4 > epsilon))
    )
  }
  return (
    segmentsCross(polygon[0]!, polygon[1]!, polygon[2]!, polygon[3]!) ||
    segmentsCross(polygon[1]!, polygon[2]!, polygon[3]!, polygon[0]!)
  )
}

/** Full gate used before perspective warp: canonical order, strict convex, simple, positive area. */
export function validateQuad(corners: readonly Point2D[], minArea = 1, epsilon = CORNER_EPSILON): QuadValidation {
  if (corners.length !== 4) return { valid: false, reason: 'WRONG_POINT_COUNT' }
  if (new Set(corners.map((p) => `${p.x},${p.y}`)).size !== 4) return { valid: false, reason: 'DUPLICATE_CORNERS' }
  if (selfIntersects(corners, epsilon)) return { valid: false, reason: 'SELF_INTERSECTING' }
  if (!isConvex(corners, epsilon)) return { valid: false, reason: 'NOT_CONVEX' }
  if (signedArea(corners) <= minArea) return { valid: false, reason: 'DEGENERATE_AREA' }
  return { valid: true, ordered: [...corners] }
}
