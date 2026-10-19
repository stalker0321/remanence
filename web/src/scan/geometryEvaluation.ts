/**
 * T333 — pure geometry EVALUATION port (no estimator, no engine, no policy).
 *
 * Technical, side-effect-free evaluation of an ALREADY estimated homography,
 * ported line-for-line from the accepted Android
 * `core/recognition/.../SiftRootSiftMatcher.kt`
 * `evaluateGeometry`/`geometryFailure`/`project`/`isStrictlyConvex`/
 * `signedPolygonArea`/`isCanonicalSupportOrientation`/`convexHullArea`/`median`
 * and its constants (`:537-751`, `:756-:889`, `:929-944`).
 *
 * Input is a copied observation: matched reference/query PIXEL points in
 * correspondence order, reference dimensions, and plain structural metadata
 * for the homography/mask that a future OpenCV bridge (NOT this module) will
 * produce. This never calls `findHomography`/RANSAC/MAGSAC, owns no runtime,
 * and carries no correspondence, engine, grant, or admission behavior. A
 * native earlier `>=6` correspondence gate and all MultiMatch/policy gates
 * remain OUTSIDE this function. `accepted` is TECHNICAL GEOMETRY ONLY.
 *
 * Refusals mirror the native check ORDER and sentinels exactly. No epsilon,
 * tolerance, normalization, calibration, or scoring threshold is introduced;
 * input feature coordinates are never mutated, logged, or persisted.
 */

/** Matched reference/query pixel point (copied observation; never mutated). */
export interface GeometryPixelPoint {
  readonly x: number
  readonly y: number
}

export const GEOMETRY_HOMOGRAPHY_TYPE_CV_64F = 6
export const GEOMETRY_MASK_TYPE_CV_8U = 0

/** Structural homography observation; `values` is row-major 3x3 when readable. */
export interface GeometryHomographyObservation {
  readonly empty: boolean
  readonly rows: number
  readonly cols: number
  readonly type: number
  /** Nine row-major cell values, or null when cells are unreadable. */
  readonly values: readonly number[] | null
}

/** Structural mask observation; `bytes` is the exact element-read sequence. */
export interface GeometryMaskObservation {
  readonly empty: boolean
  readonly rows: number
  readonly cols: number
  readonly type: number
  /** Exact element-read bytes (length === pair count), or null when unreadable. */
  readonly bytes: readonly number[] | null
}

/** Copied observation input boundary for pure evaluation. */
export interface GeometryObservationInput {
  /** Matched reference pixel points in correspondence order. */
  readonly referencePoints: readonly GeometryPixelPoint[]
  /** Matched query pixel points in correspondence order (same length). */
  readonly queryPoints: readonly GeometryPixelPoint[]
  readonly referenceWidthPx: number
  readonly referenceHeightPx: number
  readonly homography: GeometryHomographyObservation
  readonly mask: GeometryMaskObservation
}

export type GeometryFailure =
  | 'HOMOGRAPHY_EMPTY'
  | 'INVALID_HOMOGRAPHY'
  | 'INVALID_MASK'
  | 'INSUFFICIENT_INLIERS'
  | 'INVALID_PROJECTED_SUPPORT'
  | 'SUPPORT_TOO_SMALL'
  | 'SUPPORT_EDGE_RATIO'
  | 'NONE'

/** GeometryEvaluation-shaped technical result. */
export interface GeometryEvaluationResult {
  readonly found: boolean
  readonly accepted: boolean
  readonly matrix: readonly number[] | null
  readonly inlierIndices: readonly number[]
  readonly inlierRatio: number
  /** Median reprojection error, or -1.0 when no valid inlier projection. */
  readonly medianErrorPx: number
  /** Reference convex-hull coverage, or -1.0 when no valid inlier projection. */
  readonly referenceCoverage: number
  /** Absolute projected support area; zero means not observable. */
  readonly supportAreaPx2: number
  /** Projected support edge ratio; zero means not observable. */
  readonly supportEdgeRatio: number
  readonly failure: GeometryFailure
}

/** Redacted defensive shape error (inconsistent copied observation arrays). */
export class GeometryEvaluationError extends Error {
  constructor(readonly code: 'input') {
    super(code)
    this.name = 'GeometryEvaluationError'
  }
}

const HOMOGRAPHY_DIMENSION = 3
const HOMOGRAPHY_VALUES = 9
const MIN_INLIERS = 4
const MIN_SUPPORT_AREA_PX2 = 300.0
const MAX_SUPPORT_EDGE_RATIO = 8.0
const NOT_OBSERVABLE = -1.0

interface PixelPoint {
  readonly x: number
  readonly y: number
}

function geometryFailure(
  failure: GeometryFailure,
  found = false,
  matrix: number[] | null = null,
  inlierIndices: number[] = [],
  inlierRatio = 0.0,
  medianErrorPx = NOT_OBSERVABLE,
  referenceCoverage = NOT_OBSERVABLE,
  supportAreaPx2 = 0.0,
  supportEdgeRatio = 0.0,
): GeometryEvaluationResult {
  return {
    found,
    accepted: false,
    matrix,
    inlierIndices,
    inlierRatio,
    medianErrorPx,
    referenceCoverage,
    supportAreaPx2,
    supportEdgeRatio,
    failure,
  }
}

function project(matrix: readonly number[], point: PixelPoint): PixelPoint | null {
  const x = matrix[0]! * point.x + matrix[1]! * point.y + matrix[2]!
  const y = matrix[3]! * point.x + matrix[4]! * point.y + matrix[5]!
  const w = matrix[6]! * point.x + matrix[7]! * point.y + matrix[8]!
  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(w) || w === 0) return null
  const projectedX = x / w
  const projectedY = y / w
  return Number.isFinite(projectedX) && Number.isFinite(projectedY) ? { x: projectedX, y: projectedY } : null
}

function isStrictlyConvex(points: readonly PixelPoint[]): boolean {
  if (points.length < 3) return false
  let sign = 0
  for (let index = 0; index < points.length; index++) {
    const first = points[index]!
    const second = points[(index + 1) % points.length]!
    const third = points[(index + 2) % points.length]!
    const cross = (second.x - first.x) * (third.y - second.y) - (second.y - first.y) * (third.x - second.x)
    if (!Number.isFinite(cross) || cross === 0) return false
    const currentSign = cross > 0 ? 1 : -1
    if (sign !== 0 && sign !== currentSign) return false
    sign = currentSign
  }
  return true
}

function signedPolygonArea(points: readonly PixelPoint[]): number {
  let twice = 0
  for (let index = 0; index < points.length; index++) {
    const first = points[index]!
    const second = points[(index + 1) % points.length]!
    twice += first.x * second.y - second.x * first.y
  }
  return twice / 2
}

function isCanonicalSupportOrientation(signedArea: number): boolean {
  return Number.isFinite(signedArea) && signedArea > 0
}

function polygonArea(points: readonly PixelPoint[]): number {
  return Math.abs(signedPolygonArea(points))
}

/** Monotone-chain hull area with strict `<= 0` pops and exact dedup. */
function convexHullArea(points: readonly PixelPoint[]): number {
  const sorted = [...points].sort((a, b) => (a.x !== b.x ? a.x - b.x : a.y - b.y))
  const unique: PixelPoint[] = []
  for (const point of sorted) {
    const last = unique[unique.length - 1]
    if (last === undefined || last.x !== point.x || last.y !== point.y) unique.push(point)
  }
  if (unique.length < 3) return 0
  const cross = (o: PixelPoint, a: PixelPoint, b: PixelPoint): number =>
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
  const lower: PixelPoint[] = []
  for (const point of unique) {
    while (lower.length >= 2 && cross(lower[lower.length - 2]!, lower[lower.length - 1]!, point) <= 0) lower.pop()
    lower.push(point)
  }
  const upper: PixelPoint[] = []
  for (let index = unique.length - 1; index >= 0; index--) {
    const point = unique[index]!
    while (upper.length >= 2 && cross(upper[upper.length - 2]!, upper[upper.length - 1]!, point) <= 0) upper.pop()
    upper.push(point)
  }
  lower.pop()
  upper.pop()
  return polygonArea([...lower, ...upper])
}

function median(values: readonly number[]): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 1 ? sorted[middle]! : (sorted[middle - 1]! + sorted[middle]!) / 2
}

function readHomography(observation: GeometryHomographyObservation): number[] | null | 'EMPTY' {
  if (observation.empty) return 'EMPTY'
  if (observation.rows !== HOMOGRAPHY_DIMENSION || observation.cols !== HOMOGRAPHY_DIMENSION) return null
  if (observation.type !== GEOMETRY_HOMOGRAPHY_TYPE_CV_64F) return null
  const values = observation.values
  if (values === null || values.length !== HOMOGRAPHY_VALUES) return null
  const matrix: number[] = []
  for (let index = 0; index < HOMOGRAPHY_VALUES; index++) {
    const cell = values[index]
    if (typeof cell !== 'number' || !Number.isFinite(cell)) return null
    matrix.push(cell)
  }
  return matrix
}

/**
 * Pure evaluation of an already-estimated homography over copied matched
 * pixel points. Throws `GeometryEvaluationError('input')` only for an
 * inconsistent copied observation (unequal point-array lengths).
 */
export function evaluateGeometryObservation(input: GeometryObservationInput): GeometryEvaluationResult {
  if (input.referencePoints.length !== input.queryPoints.length) {
    throw new GeometryEvaluationError('input')
  }
  const pairs = input.referencePoints.length

  const read = readHomography(input.homography)
  if (read === 'EMPTY') return geometryFailure('HOMOGRAPHY_EMPTY')
  if (read === null) return geometryFailure('INVALID_HOMOGRAPHY', true)
  const matrix = read

  const mask = input.mask
  const maskShapeOk =
    (mask.rows === pairs && mask.cols === 1) || (mask.rows === 1 && mask.cols === pairs)
  if (
    mask.empty ||
    mask.type !== GEOMETRY_MASK_TYPE_CV_8U ||
    mask.rows * mask.cols !== pairs ||
    !maskShapeOk
  ) {
    return geometryFailure('INVALID_MASK', true, [...matrix])
  }
  const bytes = mask.bytes
  if (bytes === null || bytes.length !== pairs) {
    return geometryFailure('INVALID_MASK', true, [...matrix])
  }
  const inlierIndices: number[] = []
  for (let index = 0; index < pairs; index++) {
    if (bytes[index] !== 0) inlierIndices.push(index)
  }
  const inlierRatio = pairs === 0 ? 0 : inlierIndices.length / pairs

  const referenceInliers: PixelPoint[] = inlierIndices.map((index) => input.referencePoints[index]!)
  const queryInliers: PixelPoint[] = inlierIndices.map((index) => input.queryPoints[index]!)

  const validReferenceInliers: PixelPoint[] = []
  const errors: number[] = []
  for (let index = 0; index < referenceInliers.length; index++) {
    const projected = project(matrix, referenceInliers[index]!)
    if (projected === null) continue
    const queryPoint = queryInliers[index]!
    const error = Math.hypot(projected.x - queryPoint.x, projected.y - queryPoint.y)
    if (Number.isFinite(error)) {
      validReferenceInliers.push(referenceInliers[index]!)
      errors.push(error)
    }
  }

  const referenceCoverage =
    validReferenceInliers.length > 0
      ? (() => {
          const coverage = convexHullArea(validReferenceInliers) / (input.referenceWidthPx * input.referenceHeightPx)
          return Number.isFinite(coverage) ? coverage : NOT_OBSERVABLE
        })()
      : NOT_OBSERVABLE
  const medianErrorPx = errors.length > 0 ? median(errors) : NOT_OBSERVABLE

  if (inlierIndices.length < MIN_INLIERS) {
    return {
      found: true,
      accepted: false,
      matrix: [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
      supportAreaPx2: 0.0,
      supportEdgeRatio: 0.0,
      failure: 'INSUFFICIENT_INLIERS',
    }
  }
  if (errors.length !== inlierIndices.length) {
    return geometryFailure(
      'INVALID_PROJECTED_SUPPORT',
      true,
      [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
    )
  }

  const minX = Math.min(...referenceInliers.map((point) => point.x))
  const maxX = Math.max(...referenceInliers.map((point) => point.x))
  const minY = Math.min(...referenceInliers.map((point) => point.y))
  const maxY = Math.max(...referenceInliers.map((point) => point.y))
  const projectOrNull = (point: PixelPoint): PixelPoint | null => project(matrix, point)
  const projectedSupport: PixelPoint[] = []
  const supportCorners: PixelPoint[] = [
    { x: minX, y: minY },
    { x: maxX, y: minY },
    { x: maxX, y: maxY },
    { x: minX, y: maxY },
  ]
  for (const corner of supportCorners) {
    const projected = projectOrNull(corner)
    if (projected === null) {
      return geometryFailure(
        'INVALID_PROJECTED_SUPPORT',
        true,
        [...matrix],
        inlierIndices,
        inlierRatio,
        medianErrorPx,
        referenceCoverage,
      )
    }
    projectedSupport.push(projected)
  }

  const signedSupportArea = signedPolygonArea(projectedSupport)
  const supportArea = Math.abs(signedSupportArea)
  const edgeLengths = projectedSupport.map((point, index) => {
    const next = projectedSupport[(index + 1) % projectedSupport.length]!
    return Math.hypot(next.x - point.x, next.y - point.y)
  })
  const shortestEdge = edgeLengths.length > 0 ? Math.min(...edgeLengths) : 0
  const longestEdge = edgeLengths.length > 0 ? Math.max(...edgeLengths) : 0
  const supportEdgeRatio =
    shortestEdge > 0 && Number.isFinite(shortestEdge) && Number.isFinite(longestEdge)
      ? longestEdge / shortestEdge
      : 0
  const reportedSupportArea = Number.isFinite(supportArea) ? supportArea : 0
  const reportedSupportEdgeRatio = Number.isFinite(supportEdgeRatio) ? supportEdgeRatio : 0

  if (!isStrictlyConvex(projectedSupport)) {
    return geometryFailure(
      'INVALID_PROJECTED_SUPPORT',
      true,
      [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
      reportedSupportArea,
      reportedSupportEdgeRatio,
    )
  }
  if (!isCanonicalSupportOrientation(signedSupportArea)) {
    return geometryFailure(
      'INVALID_PROJECTED_SUPPORT',
      true,
      [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
      reportedSupportArea,
      reportedSupportEdgeRatio,
    )
  }
  if (supportArea < MIN_SUPPORT_AREA_PX2) {
    return {
      found: true,
      accepted: false,
      matrix: [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
      supportAreaPx2: supportArea,
      supportEdgeRatio: reportedSupportEdgeRatio,
      failure: 'SUPPORT_TOO_SMALL',
    }
  }
  if (
    shortestEdge <= 0 ||
    !Number.isFinite(shortestEdge) ||
    !Number.isFinite(longestEdge) ||
    longestEdge / shortestEdge > MAX_SUPPORT_EDGE_RATIO
  ) {
    return {
      found: true,
      accepted: false,
      matrix: [...matrix],
      inlierIndices,
      inlierRatio,
      medianErrorPx,
      referenceCoverage,
      supportAreaPx2: supportArea,
      supportEdgeRatio: reportedSupportEdgeRatio,
      failure: 'SUPPORT_EDGE_RATIO',
    }
  }
  return {
    found: true,
    accepted: true,
    matrix: [...matrix],
    inlierIndices,
    inlierRatio,
    medianErrorPx,
    referenceCoverage,
    supportAreaPx2: supportArea,
    supportEdgeRatio: reportedSupportEdgeRatio,
    failure: 'NONE',
  }
}
