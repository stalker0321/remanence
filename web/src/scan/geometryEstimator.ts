/**
 * T337 — async convenience wrapper: matched pixel points → real pinned
 * homography estimation → accepted T333 pure evaluation.
 *
 * Technical geometry only (never matched/grant/admission). Snapshots every
 * consumed coordinate/count/dimension synchronously BEFORE the first await,
 * then uses only those snapshots. The native estimator call itself is
 * synchronous and not interruptible; the optional AbortSignal is checked
 * before/after the runtime load and after the call, and a late abort never
 * yields accepted geometry.
 */

import type { RawCv } from './opencvRuntime'
import { loadOpenCvModule } from './opencvRuntime'
import {
  evaluateGeometryObservation,
  GeometryEvaluationError,
  type GeometryEvaluationResult,
  type GeometryPixelPoint,
} from './geometryEvaluation'
import {
  estimateHomographyObservations,
  GeometryBridgeError,
  GEOMETRY_MAX_POINTS,
  GEOMETRY_MIN_ESTIMATOR_POINTS,
} from './opencvGeometryPort'

export type GeometryEstimatorErrorCode = 'input' | 'unavailable' | 'cancelled' | 'contract' | 'internal'

/** Redacted, bounded wrapper refusal (no coordinates/feature data). */
export class GeometryEstimatorError extends Error {
  constructor(readonly code: GeometryEstimatorErrorCode) {
    super(code)
    this.name = 'GeometryEstimatorError'
  }
}

export interface EstimateAndEvaluateInput {
  /** Matched reference pixel points in correspondence order (DOUBLE). */
  readonly referencePoints: readonly GeometryPixelPoint[]
  /** Matched query pixel points in correspondence order (DOUBLE). */
  readonly queryPoints: readonly GeometryPixelPoint[]
  readonly referenceWidthPx: number
  readonly referenceHeightPx: number
  readonly signal?: AbortSignal
}

export interface EstimateAndEvaluateDeps {
  /** Test seam only; production default is the shared pinned loader. */
  readonly loadModule?: (signal?: AbortSignal) => Promise<RawCv>
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) throw new GeometryEstimatorError('cancelled')
}

function snapshotPoints(
  points: readonly GeometryPixelPoint[],
  label: 'reference' | 'query',
): GeometryPixelPoint[] {
  if (!Array.isArray(points)) throw new GeometryEstimatorError('input')
  const copied: GeometryPixelPoint[] = []
  for (const point of points) {
    if (
      point === null ||
      typeof point !== 'object' ||
      typeof point.x !== 'number' ||
      typeof point.y !== 'number' ||
      !Number.isFinite(point.x) ||
      !Number.isFinite(point.y)
    ) {
      throw new GeometryEstimatorError('input')
    }
    copied.push({ x: point.x, y: point.y })
  }
  void label
  return copied
}

function mapBridgeError(error: unknown): GeometryEstimatorError {
  if (error instanceof GeometryEstimatorError) return error
  if (error instanceof GeometryBridgeError) {
    if (error.code === 'input') return new GeometryEstimatorError('input')
    if (error.code === 'native-contract') return new GeometryEstimatorError('contract')
    if (error.code === 'unavailable') return new GeometryEstimatorError('unavailable')
    return new GeometryEstimatorError('internal')
  }
  if (error instanceof GeometryEvaluationError) return new GeometryEstimatorError('input')
  return new GeometryEstimatorError('internal')
}

/**
 * Snapshot → load → real bridge → T333 evaluation. `accepted` in the returned
 * result is TECHNICAL geometry only.
 */
export async function estimateAndEvaluateGeometry(
  input: EstimateAndEvaluateInput,
  deps: EstimateAndEvaluateDeps = {},
): Promise<GeometryEvaluationResult> {
  // --- synchronous snapshot/validation BEFORE any await ---
  const referencePoints = snapshotPoints(input.referencePoints, 'reference')
  const queryPoints = snapshotPoints(input.queryPoints, 'query')
  if (referencePoints.length !== queryPoints.length) throw new GeometryEstimatorError('input')
  const pairCount = referencePoints.length
  if (pairCount < GEOMETRY_MIN_ESTIMATOR_POINTS || pairCount > GEOMETRY_MAX_POINTS) {
    throw new GeometryEstimatorError('input')
  }
  const referenceWidthPx = input.referenceWidthPx
  const referenceHeightPx = input.referenceHeightPx
  if (
    typeof referenceWidthPx !== 'number' ||
    typeof referenceHeightPx !== 'number' ||
    !Number.isFinite(referenceWidthPx) ||
    !Number.isFinite(referenceHeightPx) ||
    referenceWidthPx <= 0 ||
    referenceHeightPx <= 0
  ) {
    throw new GeometryEstimatorError('input')
  }
  const signal = input.signal

  throwIfAborted(signal)
  const load = deps.loadModule ?? loadOpenCvModule
  let cv: RawCv
  try {
    cv = await load(signal)
  } catch (error) {
    if (signal?.aborted) throw new GeometryEstimatorError('cancelled')
    if (error instanceof GeometryEstimatorError) throw error
    throw new GeometryEstimatorError('unavailable')
  }
  throwIfAborted(signal)

  let observations: ReturnType<typeof estimateHomographyObservations>
  try {
    observations = estimateHomographyObservations(cv, referencePoints, queryPoints)
  } catch (error) {
    throw mapBridgeError(error)
  }
  // Post-call fence: a late abort must not yield accepted geometry.
  throwIfAborted(signal)

  try {
    return evaluateGeometryObservation({
      referencePoints,
      queryPoints,
      referenceWidthPx,
      referenceHeightPx,
      homography: observations.homography,
      mask: observations.mask,
    })
  } catch (error) {
    throw mapBridgeError(error)
  }
}
