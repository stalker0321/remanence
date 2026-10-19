/**
 * T339 — pure native-equivalent post-geometry side scoring.
 *
 * Mirrors the protected Android recognition policy for ONE query/reference side:
 * `LocalMatchEngine.evaluateSide` / `fullCardPlausible` /
 * `normalizePixelHomography`, `SpatialCoverage.kt`, `HomographyPlausibility.kt`,
 * `SideScorer.kt`, and the frozen `postcard-sift-rootsift-v1` thresholds.
 *
 * It consumes accepted plain data (a `SiftFingerprint` pair, the accepted
 * correspondence pairs/`uniqueMatches`, and a `GeometryEvaluationResult`) and
 * returns raw `SideMatchSignals` + `SideScoreReport` (+ pure diagnostics). It is
 * NOT ranking/winner selection, recognition-engine activation, Scan or a grant,
 * and it never mutates its inputs. Malformed/inconsistent input fails closed
 * with a redacted typed error; it is never repaired into acceptance.
 */

import type { CorrespondencePair } from './descriptorCorrespondence'
import type { GeometryEvaluationResult } from './geometryEvaluation'
import type { SiftFingerprint } from './siftFingerprint'

/** Frozen active constants of `RecognitionProfile.postcardSiftRootSiftV1()`. */
export const SIDE_SCORING_PROFILE = Object.freeze({
  canonicalLongEdgePx: 1600,
  inlierReprojectionTolerancePx: 5.0,
  countScoreInliersDivisor: 40.0,
  ratioScoreOffset: 0.2,
  ratioScoreSpan: 0.6,
  coverageScoreTarget: 0.45,
  reprojectionScoreMaxMedianErrorPx: 8.0,
  coverageGridSize: 4,
  weakMinRatioMatches: 10,
  weakMinInliers: 6,
  weakMinInlierRatio: 0.25,
  weakMinCoverage: 0.1,
  weakMinGridCells: 3,
  strongMinRatioMatches: 20,
  strongMinInliers: 15,
  strongMinInlierRatio: 0.35,
  strongMinCoverage: 0.2,
  strongMaxMedianErrorPx: 4.0,
  homographyAreaRatioMin: 0.2,
  homographyAreaRatioMax: 5.0,
  homographyMaxOppositeEdgeRatio: 4.0,
  countWeight: 0.35,
  ratioWeight: 0.25,
  coverageWeight: 0.25,
  reprojectionWeight: 0.15,
} as const)

const MICRO_UNITS = 1_000_000
const NORMALIZED_MEDIAN_SENTINEL = 1.0
const AREA_RATIO_SCALE = 1.0
const NORMALIZED_EPSILON = 1e-12

export type SideScoringErrorCode = 'input'

/** Redacted defensive refusal (never carries feature data). */
export class SideScoringError extends Error {
  constructor(readonly code: SideScoringErrorCode) {
    super(code)
    this.name = 'SideScoringError'
  }
}

function refuse(): SideScoringError {
  return new SideScoringError('input')
}

export interface SideMatchSignals {
  ratioMutualMatches: number
  ransacInliers: number
  inlierRatio: number
  spatialCoverage: number
  occupiedGridCells: number
  medianInlierErrorNormalized: number
  homographyPlausible: boolean
}

export interface SideScoreReport {
  countScore: number
  ratioScore: number
  coverageScore: number
  reprojectionScore: number
  sideScore: number
  weakGatePassed: boolean
  strongGatePassed: boolean
}

export interface SpatialCoverageResult {
  queryHullAreaNormalized: number
  referenceHullAreaNormalized: number
  queryOccupiedCells: number
  referenceOccupiedCells: number
  /** Binding (min) normalized hull area. */
  hullAreaNormalized: number
  /** Binding (min) occupied grid cells. */
  occupiedGridCells: number
}

export interface HomographyPlausibilityReport {
  finiteQuad: boolean
  convexQuad: boolean
  orientationPreserved: boolean
  mappedAreaRatio: number
  maxOppositeEdgeRatio: number
  medianErrorWithinLimit: boolean
  plausible: boolean
}

export interface SideScoringInput {
  readonly query: SiftFingerprint
  readonly reference: SiftFingerprint
  readonly pairs: readonly CorrespondencePair[]
  /** `CorrespondenceResult.diagnostics.uniqueMatches`. */
  readonly uniqueMatches: number
  readonly geometry: GeometryEvaluationResult
}

export interface SideScoringDiagnostics extends SpatialCoverageResult {
  readonly medianErrorPx: number
  readonly medianInlierErrorNormalized: number
  readonly fullCardPlausibility: HomographyPlausibilityReport | null
}

export interface SideScoringResult {
  readonly signals: SideMatchSignals
  readonly report: SideScoreReport
  readonly diagnostics: SideScoringDiagnostics
}

type Point = readonly [number, number]

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}

function clampUnit(value: number): number {
  return Math.min(1, Math.max(0, value))
}

function cross(o: Point, a: Point, b: Point): number {
  return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
}

function monotoneChain(points: readonly Point[]): Point[] {
  const sorted = [...points].sort((left, right) => left[0] - right[0] || left[1] - right[1])
  const unique: Point[] = []
  for (const point of sorted) {
    const last = unique[unique.length - 1]
    if (last === undefined || last[0] !== point[0] || last[1] !== point[1]) unique.push(point)
  }
  if (unique.length < 3) return unique
  const lower: Point[] = []
  for (const point of unique) {
    while (lower.length >= 2 && cross(lower[lower.length - 2]!, lower[lower.length - 1]!, point) <= 0) lower.pop()
    lower.push(point)
  }
  const upper: Point[] = []
  for (const point of [...unique].reverse()) {
    while (upper.length >= 2 && cross(upper[upper.length - 2]!, upper[upper.length - 1]!, point) <= 0) upper.pop()
    upper.push(point)
  }
  lower.pop()
  upper.pop()
  return [...lower, ...upper]
}

/** Monotone-chain convex-hull shoelace area over normalized coordinates. */
export function convexHullArea(points: readonly Point[]): number {
  if (points.length < 3) return 0
  const hull = monotoneChain(points)
  if (hull.length < 3) return 0
  let twice = 0
  for (let index = 0; index < hull.length; index++) {
    const a = hull[index]!
    const b = hull[(index + 1) % hull.length]!
    twice += a[0] * b[1] - b[0] * a[1]
  }
  return Math.abs(twice) / 2
}

function occupiedCells(points: readonly Point[], gridSize: number): number {
  const cells = new Set<number>()
  for (const [x, y] of points) {
    if (Number.isFinite(x) && Number.isFinite(y)) {
      let col = Math.trunc(x * gridSize)
      let row = Math.trunc(y * gridSize)
      if (col >= gridSize) col = gridSize - 1
      if (row >= gridSize) row = gridSize - 1
      if (col >= 0 && row >= 0) cells.add(row * gridSize + col)
    }
  }
  return cells.size
}

/** Recomputes BOTH sides; the binding value is the smaller one (native). */
export function measureSpatialCoverage(
  queryPoints: readonly Point[],
  referencePoints: readonly Point[],
  gridSize: number = SIDE_SCORING_PROFILE.coverageGridSize,
): SpatialCoverageResult {
  if (queryPoints.length === 0 && referencePoints.length === 0) {
    return {
      queryHullAreaNormalized: 0,
      referenceHullAreaNormalized: 0,
      queryOccupiedCells: 0,
      referenceOccupiedCells: 0,
      hullAreaNormalized: 0,
      occupiedGridCells: 0,
    }
  }
  const queryHullAreaNormalized = convexHullArea(queryPoints)
  const referenceHullAreaNormalized = convexHullArea(referencePoints)
  const queryOccupiedCells = occupiedCells(queryPoints, gridSize)
  const referenceOccupiedCells = occupiedCells(referencePoints, gridSize)
  return {
    queryHullAreaNormalized,
    referenceHullAreaNormalized,
    queryOccupiedCells,
    referenceOccupiedCells,
    hullAreaNormalized: Math.min(queryHullAreaNormalized, referenceHullAreaNormalized),
    occupiedGridCells: Math.min(queryOccupiedCells, referenceOccupiedCells),
  }
}

function multiply3x3(left: readonly number[], right: readonly number[]): number[] {
  const result = new Array<number>(9)
  for (let row = 0; row < 3; row++) {
    for (let column = 0; column < 3; column++) {
      let value = 0
      for (let inner = 0; inner < 3; inner++) {
        value += left[row * 3 + inner]! * right[inner * 3 + column]!
      }
      result[row * 3 + column] = value
    }
  }
  return result
}

/**
 * Converts P2's row-major reference-pixel -> query-pixel matrix into normalized
 * reference -> query coordinates as `Dquery^-1 * (Hpixels * Dreference)` (the
 * exact native operation order, not an algebraically reassociated form).
 */
export function normalizePixelHomography(
  pixelMatrix: readonly number[],
  queryWidthPx: number,
  queryHeightPx: number,
  referenceWidthPx: number,
  referenceHeightPx: number,
): number[] {
  const dReference = [referenceWidthPx, 0, 0, 0, referenceHeightPx, 0, 0, 0, 1]
  const inverseDQuery = [1 / queryWidthPx, 0, 0, 0, 1 / queryHeightPx, 0, 0, 0, 1]
  return multiply3x3(inverseDQuery, multiply3x3(pixelMatrix, dReference))
}

function convexNonSelfIntersecting(quad: readonly Point[]): boolean {
  let sign = 0
  for (let index = 0; index < quad.length; index++) {
    const o = quad[index]!
    const a = quad[(index + 1) % quad.length]!
    const b = quad[(index + 2) % quad.length]!
    const turn = cross(o, a, b)
    if (turn > NORMALIZED_EPSILON) {
      if (sign < 0) return false
      sign = 1
    } else if (turn < -NORMALIZED_EPSILON) {
      if (sign > 0) return false
      sign = -1
    } else {
      return false
    }
  }
  return true
}

const NORMALIZED_CARD_CORNERS: readonly Point[] = [
  [0, 0],
  [1, 0],
  [1, 1],
  [0, 1],
]

/** Exact `HomographyPlausibilityGate.check` for the frozen unit-card corners. */
export function checkHomographyPlausibility(
  matrix: readonly number[],
  medianInlierErrorNormalized: number,
  medianErrorLimitNormalized: number = SIDE_SCORING_PROFILE.inlierReprojectionTolerancePx / SIDE_SCORING_PROFILE.canonicalLongEdgePx,
): HomographyPlausibilityReport {
  if (matrix.length !== 9) throw refuse()
  const transformed = NORMALIZED_CARD_CORNERS.map(([x, y]) => {
    const dx = matrix[0]! * x + matrix[1]! * y + matrix[2]!
    const dy = matrix[3]! * x + matrix[4]! * y + matrix[5]!
    const dw = matrix[6]! * x + matrix[7]! * y + matrix[8]!
    return [dx, dy, dw] as const
  })
  const finite = transformed.every(
    ([dx, dy, dw]) => dw !== 0 && Number.isFinite(dx) && Number.isFinite(dy) && Number.isFinite(Math.abs(dw)),
  )
  if (!finite) {
    return {
      finiteQuad: false,
      convexQuad: false,
      orientationPreserved: false,
      mappedAreaRatio: 0,
      maxOppositeEdgeRatio: Number.MAX_VALUE,
      medianErrorWithinLimit: false,
      plausible: false,
    }
  }
  const positiveW =
    transformed.every(([, , dw]) => dw > 0) || transformed.every(([, , dw]) => dw < 0)
  const quad: Point[] = transformed.map(([dx, dy, dw]) => [dx / dw, dy / dw] as const)
  const allFinitePoints = quad.every(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  const finiteQuad = positiveW && allFinitePoints

  let signedAreaTwice = 0
  for (let index = 0; index < quad.length; index++) {
    const a = quad[index]!
    const b = quad[(index + 1) % quad.length]!
    signedAreaTwice += a[0] * b[1] - b[0] * a[1]
  }
  const orientationPreserved = signedAreaTwice > NORMALIZED_EPSILON
  const convexQuad = convexNonSelfIntersecting(quad)
  const mappedAreaRatio = Math.abs(signedAreaTwice) / 2 / AREA_RATIO_SCALE

  const edgeLengths = quad.map((point, index) => {
    const next = quad[(index + 1) % quad.length]!
    const ex = next[0] - point[0]
    const ey = next[1] - point[1]
    return Math.sqrt(ex * ex + ey * ey)
  })
  let maxOppositeEdgeRatio = 1
  for (let index = 0; index < 2; index++) {
    const short = Math.min(edgeLengths[index]!, edgeLengths[index + 2]!)
    const long = Math.max(edgeLengths[index]!, edgeLengths[index + 2]!)
    if (short <= NORMALIZED_EPSILON) {
      maxOppositeEdgeRatio = Number.MAX_VALUE
      break
    }
    maxOppositeEdgeRatio = Math.max(maxOppositeEdgeRatio, long / short)
  }

  const medianErrorWithinLimit = medianInlierErrorNormalized <= medianErrorLimitNormalized
  const plausible =
    finiteQuad &&
    convexQuad &&
    orientationPreserved &&
    mappedAreaRatio >= SIDE_SCORING_PROFILE.homographyAreaRatioMin &&
    mappedAreaRatio <= SIDE_SCORING_PROFILE.homographyAreaRatioMax &&
    maxOppositeEdgeRatio <= SIDE_SCORING_PROFILE.homographyMaxOppositeEdgeRatio &&
    medianErrorWithinLimit
  return {
    finiteQuad,
    convexQuad,
    orientationPreserved,
    mappedAreaRatio,
    maxOppositeEdgeRatio,
    medianErrorWithinLimit,
    plausible,
  }
}

/** Exact `SideScorer.score`: clamps only the computed sub-scores. */
export function scoreSide(signals: SideMatchSignals): SideScoreReport {
  const profile = SIDE_SCORING_PROFILE
  const longEdge = profile.canonicalLongEdgePx
  const countScore = clampUnit(signals.ransacInliers / profile.countScoreInliersDivisor)
  const ratioScore = clampUnit((signals.inlierRatio - profile.ratioScoreOffset) / profile.ratioScoreSpan)
  const coverageScore = clampUnit(signals.spatialCoverage / profile.coverageScoreTarget)
  const medianErrorPx = signals.medianInlierErrorNormalized * longEdge
  const reprojectionScore = 1 - clampUnit(medianErrorPx / profile.reprojectionScoreMaxMedianErrorPx)
  const sideScore =
    profile.countWeight * countScore +
    profile.ratioWeight * ratioScore +
    profile.coverageWeight * coverageScore +
    profile.reprojectionWeight * reprojectionScore
  const weakGatePassed =
    signals.homographyPlausible &&
    signals.ratioMutualMatches >= profile.weakMinRatioMatches &&
    signals.ransacInliers >= profile.weakMinInliers &&
    signals.inlierRatio >= profile.weakMinInlierRatio &&
    signals.spatialCoverage >= profile.weakMinCoverage &&
    signals.occupiedGridCells >= profile.weakMinGridCells
  const strongGatePassed =
    signals.homographyPlausible &&
    signals.ratioMutualMatches >= profile.strongMinRatioMatches &&
    signals.ransacInliers >= profile.strongMinInliers &&
    signals.inlierRatio >= profile.strongMinInlierRatio &&
    signals.spatialCoverage >= profile.strongMinCoverage &&
    medianErrorPx <= profile.strongMaxMedianErrorPx
  return {
    countScore,
    ratioScore,
    coverageScore,
    reprojectionScore,
    sideScore,
    weakGatePassed,
    strongGatePassed,
  }
}

function requireFinitePoint(xMicro: number, yMicro: number): Point {
  if (!Number.isFinite(xMicro) || !Number.isFinite(yMicro)) throw refuse()
  return [xMicro / MICRO_UNITS, yMicro / MICRO_UNITS] as const
}

/**
 * Pure mirror of `LocalMatchEngine.evaluateSide` over already-corresponded data.
 * Never returns an acceptance/winner; `homographyPlausible` is evidence only.
 */
export function evaluateSide(input: SideScoringInput): SideScoringResult {
  const { query, reference, pairs, uniqueMatches, geometry } = input
  if (!Array.isArray(pairs) || !geometry || typeof geometry !== 'object') throw refuse()
  if (!isInteger(uniqueMatches) || uniqueMatches < 0) throw refuse()
  if (!Array.isArray(geometry.inlierIndices)) throw refuse()
  if (!Number.isFinite(geometry.inlierRatio)) throw refuse()

  const queryPoints: Point[] = []
  const referencePoints: Point[] = []
  for (const rawIndex of geometry.inlierIndices) {
    if (!isInteger(rawIndex) || rawIndex < 0 || rawIndex >= pairs.length) throw refuse()
    const pair = pairs[rawIndex]!
    if (!isInteger(pair.queryIndex) || !isInteger(pair.referenceIndex)) throw refuse()
    const queryKeypoint = query.keypoints[pair.queryIndex]
    const referenceKeypoint = reference.keypoints[pair.referenceIndex]
    if (!queryKeypoint || !referenceKeypoint) throw refuse()
    queryPoints.push(requireFinitePoint(queryKeypoint.xMicro, queryKeypoint.yMicro))
    referencePoints.push(requireFinitePoint(referenceKeypoint.xMicro, referenceKeypoint.yMicro))
  }

  const coverage = measureSpatialCoverage(queryPoints, referencePoints)
  const occupiedGridCells = queryPoints.length === 0 ? 0 : coverage.occupiedGridCells
  const spatialCoverage =
    queryPoints.length === 0 ? 0 : Number.isFinite(coverage.hullAreaNormalized) && coverage.hullAreaNormalized >= 0
      ? coverage.hullAreaNormalized
      : 0

  const medianErrorPx = geometry.medianErrorPx
  const medianInlierErrorNormalized =
    Number.isFinite(medianErrorPx) && medianErrorPx >= 0
      ? medianErrorPx / SIDE_SCORING_PROFILE.canonicalLongEdgePx
      : NORMALIZED_MEDIAN_SENTINEL

  let fullCardPlausibility: HomographyPlausibilityReport | null = null
  if (geometry.accepted) {
    const { matrix } = geometry
    if (matrix !== null && matrix.length === 9) {
      const { canonicalWidthPx: queryWidthPx, canonicalHeightPx: queryHeightPx } = query
      const { canonicalWidthPx: referenceWidthPx, canonicalHeightPx: referenceHeightPx } = reference
      if (
        !isInteger(queryWidthPx) || queryWidthPx < 1 ||
        !isInteger(queryHeightPx) || queryHeightPx < 1 ||
        !isInteger(referenceWidthPx) || referenceWidthPx < 1 ||
        !isInteger(referenceHeightPx) || referenceHeightPx < 1
      ) {
        throw refuse()
      }
      const normalizedMatrix = normalizePixelHomography(
        matrix,
        queryWidthPx,
        queryHeightPx,
        referenceWidthPx,
        referenceHeightPx,
      )
      fullCardPlausibility = checkHomographyPlausibility(normalizedMatrix, medianInlierErrorNormalized)
    }
  }

  const signals: SideMatchSignals = {
    ratioMutualMatches: uniqueMatches,
    ransacInliers: geometry.inlierIndices.length,
    inlierRatio: geometry.inlierRatio,
    spatialCoverage,
    occupiedGridCells,
    medianInlierErrorNormalized,
    homographyPlausible: geometry.accepted && fullCardPlausibility !== null && fullCardPlausibility.plausible,
  }
  return {
    signals,
    report: scoreSide(signals),
    diagnostics: {
      ...coverage,
      medianErrorPx,
      medianInlierErrorNormalized,
      fullCardPlausibility,
    },
  }
}
