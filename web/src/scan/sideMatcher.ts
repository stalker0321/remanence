/**
 * T341 — connected real single-side matching pipeline.
 *
 * Ports the native `SiftRootSiftMatcher.match` + `LocalMatchEngine.evaluateSide`
 * boundary as ONE connected side computation over two accepted format-3
 * fingerprints:
 *
 *   snapshot+validate → accepted `matchDescriptors` (REAL pinned OpenCV
 *   default) → `>= 6` unique-pairs gate → DOUBLE reference/query pixel mapping
 *   from the owned snapshots → accepted `estimateAndEvaluateGeometry` (REAL
 *   pinned estimator) → accepted `evaluateSide` (unchanged policy).
 *
 * Technical visual evidence only: it never chooses a winner, activates an
 * engine, issues a grant, or admits a scan. Early no-evidence (no usable rows /
 * no ratio / no reciprocal / < 6 unique) skips geometry and returns truthful
 * native-style sentinel signals and low/no gates, not a forced success. Runtime
 * unavailability and contract faults are redacted typed errors, never a fake
 * NO_MATCH result.
 *
 * Ownership: every consumed fingerprint (descriptor rows/keypoints/dimensions/
 * profile/coarse hash) is copied synchronously before the first await; later
 * point mapping and policy use those owned snapshots only. Owned descriptor rows
 * and temporary point arrays are wiped on success/failure/abort; caller
 * fingerprints are never mutated or wiped. No native handle/view/descriptor
 * buffer leaves this module.
 */

import {
  matchDescriptors,
  DescriptorCorrespondenceError,
  type CorrespondenceResult,
} from './descriptorCorrespondence'
import {
  estimateAndEvaluateGeometry,
  GeometryEstimatorError,
} from './geometryEstimator'
import type { GeometryEvaluationResult, GeometryFailure } from './geometryEvaluation'
import {
  createPinnedMatcherRuntime,
  PinnedMatcherError,
  type PinnedMatcherRuntime,
} from './opencvMatcherPort'
import { loadOpenCvModule } from './opencvRuntime'
import {
  evaluateSide,
  scoreSide,
  SideScoringError,
  type SideMatchSignals,
  type SideScoreReport,
} from './sideScoring'
import {
  validateSiftFingerprintShape,
  type SiftFingerprint,
  type SiftFingerprintKeypoint,
} from './siftFingerprint'

export const MIN_GEOMETRY_PAIRS = 6

const MICRO_UNITS = 1_000_000
const NORMALIZED_MEDIAN_SENTINEL = 1.0
const NOT_OBSERVABLE = -1

export type SideMatchErrorCode = 'contract' | 'unavailable' | 'cancelled' | 'stale' | 'internal'

/** Redacted connected-pipeline refusal (no feature data). */
export class SideMatchError extends Error {
  constructor(readonly code: SideMatchErrorCode) {
    super(code)
    this.name = 'SideMatchError'
  }
}

export type SideMatchFailure =
  | 'NO_USABLE_ROWS'
  | 'NO_RATIO_MATCHES'
  | 'NO_RECIPROCAL_MATCHES'
  | 'INSUFFICIENT_UNIQUE_PAIRS'
  | GeometryFailure

export interface SideMatchDiagnostics {
  readonly failure: SideMatchFailure
  readonly geometryAttempted: boolean
  readonly geometryFound: boolean
  readonly geometryAccepted: boolean
  readonly uniqueMatches: number
  readonly inliers: number
  readonly medianErrorPx: number
  readonly referenceCoverage: number
  readonly supportAreaPx2: number
  readonly supportEdgeRatio: number
}

export interface SideMatchResult {
  readonly correspondence: CorrespondenceResult
  readonly geometry: GeometryEvaluationResult | null
  readonly signals: SideMatchSignals
  readonly report: SideScoreReport
  readonly diagnostics: SideMatchDiagnostics
}

export interface SideMatcherOptions {
  readonly signal?: AbortSignal
  /** Optional current-owner fence; false/throw refuses with `stale`. */
  readonly fence?: () => boolean
  /** Test seam only; production default is the real pinned matcher loader. */
  readonly matcherFactory?: () => Promise<PinnedMatcherRuntime>
  /** Test seam only; production default is the accepted correspondence stage. */
  readonly matchCorrespondence?: typeof matchDescriptors
  /** Test seam only; production default is the accepted geometry estimator. */
  readonly estimateGeometry?: typeof estimateAndEvaluateGeometry
}

function snapshotFingerprint(fingerprint: SiftFingerprint): SiftFingerprint {
  const rows: Uint8Array[] = []
  try {
    validateSiftFingerprintShape(fingerprint)
    for (const row of fingerprint.quantizedSiftDescriptors) rows.push(row.slice())
    return {
      profileId: fingerprint.profileId,
      canonicalWidthPx: fingerprint.canonicalWidthPx,
      canonicalHeightPx: fingerprint.canonicalHeightPx,
      coarseHash64: fingerprint.coarseHash64,
      keypoints: fingerprint.keypoints.map((keypoint) => ({ ...keypoint })),
      quantizedSiftDescriptors: rows,
    }
  } catch {
    for (const row of rows) row.fill(0)
    throw new SideMatchError('contract')
  }
}

function wipeFingerprint(fingerprint: SiftFingerprint): void {
  for (const row of fingerprint.quantizedSiftDescriptors) row.fill(0)
}

interface MutablePoint {
  x: number
  y: number
}

function wipePoints(points: MutablePoint[] | null): void {
  if (!points) return
  for (const point of points) {
    point.x = 0
    point.y = 0
  }
  points.length = 0
}

/** Native `pointFor`: normalized micro-units → DOUBLE pixels of that side. */
function pointFor(fingerprint: SiftFingerprint, keypoint: SiftFingerprintKeypoint): MutablePoint {
  return {
    x: (keypoint.xMicro / MICRO_UNITS) * fingerprint.canonicalWidthPx,
    y: (keypoint.yMicro / MICRO_UNITS) * fingerprint.canonicalHeightPx,
  }
}

function keypointAt(fingerprint: SiftFingerprint, index: number): SiftFingerprintKeypoint {
  const keypoint = fingerprint.keypoints[index]
  if (!keypoint) throw new SideMatchError('contract')
  return keypoint
}

function checkGate(options: SideMatcherOptions): void {
  if (options.signal?.aborted) throw new SideMatchError('cancelled')
  if (options.fence) {
    let current = false
    try {
      current = options.fence()
    } catch {
      current = false
    }
    if (!current) throw new SideMatchError('stale')
  }
}

function failureForNoEvidence(noEvidence: string | null): SideMatchFailure {
  switch (noEvidence) {
    case 'no-usable-rows':
      return 'NO_USABLE_ROWS'
    case 'no-ratio-matches':
      return 'NO_RATIO_MATCHES'
    case 'no-reciprocal-matches':
      return 'NO_RECIPROCAL_MATCHES'
    case 'insufficient-unique-pairs':
      return 'INSUFFICIENT_UNIQUE_PAIRS'
    default:
      return 'INSUFFICIENT_UNIQUE_PAIRS'
  }
}

function copyCorrespondence(correspondence: CorrespondenceResult): CorrespondenceResult {
  return {
    pairs: correspondence.pairs.map((pair) => ({ ...pair })),
    diagnostics: { ...correspondence.diagnostics },
  }
}

function copyGeometry(geometry: GeometryEvaluationResult): GeometryEvaluationResult {
  return {
    ...geometry,
    matrix: geometry.matrix === null ? null : [...geometry.matrix],
    inlierIndices: [...geometry.inlierIndices],
  }
}

function noGeometryResult(correspondence: CorrespondenceResult, failure: SideMatchFailure): SideMatchResult {
  const uniqueMatches = correspondence.diagnostics.uniqueMatches
  const signals: SideMatchSignals = {
    ratioMutualMatches: uniqueMatches,
    ransacInliers: 0,
    inlierRatio: 0,
    spatialCoverage: 0,
    occupiedGridCells: 0,
    medianInlierErrorNormalized: NORMALIZED_MEDIAN_SENTINEL,
    homographyPlausible: false,
  }
  return {
    correspondence: copyCorrespondence(correspondence),
    geometry: null,
    signals,
    report: scoreSide(signals),
    diagnostics: {
      failure,
      geometryAttempted: false,
      geometryFound: false,
      geometryAccepted: false,
      uniqueMatches,
      inliers: 0,
      medianErrorPx: NOT_OBSERVABLE,
      referenceCoverage: NOT_OBSERVABLE,
      supportAreaPx2: 0,
      supportEdgeRatio: 0,
    },
  }
}

function mapCorrespondenceError(error: unknown, signal: AbortSignal | undefined): SideMatchError {
  if (signal?.aborted) return new SideMatchError('cancelled')
  if (error instanceof DescriptorCorrespondenceError) {
    if (error.code === 'cancelled') return new SideMatchError('cancelled')
    if (error.code === 'unavailable') return new SideMatchError('unavailable')
    if (error.code === 'shape' || error.code === 'native-contract') return new SideMatchError('contract')
    return new SideMatchError('internal')
  }
  // Real `matchDescriptors` propagates accepted PinnedMatcherError unchanged;
  // preserve its provenance instead of collapsing native faults to `internal`.
  // Unknown native failures stay `internal`; abort precedence is checked above.
  if (error instanceof PinnedMatcherError) {
    if (error.code === 'unavailable') return new SideMatchError('unavailable')
    if (error.code === 'native-contract') return new SideMatchError('contract')
    return new SideMatchError('internal')
  }
  return new SideMatchError('internal')
}

function mapGeometryError(error: unknown, signal: AbortSignal | undefined): SideMatchError {
  if (signal?.aborted) return new SideMatchError('cancelled')
  if (error instanceof GeometryEstimatorError) {
    if (error.code === 'cancelled') return new SideMatchError('cancelled')
    if (error.code === 'unavailable') return new SideMatchError('unavailable')
    if (error.code === 'input' || error.code === 'contract') return new SideMatchError('contract')
    return new SideMatchError('internal')
  }
  return new SideMatchError('internal')
}

function mapScoringError(error: unknown): SideMatchError {
  if (error instanceof SideScoringError) return new SideMatchError('contract')
  return new SideMatchError('internal')
}

function defaultMatcherFactory(signal: AbortSignal | undefined): () => Promise<PinnedMatcherRuntime> {
  return async () => createPinnedMatcherRuntime(await loadOpenCvModule(signal))
}

/**
 * Runs the whole connected side pipeline over two accepted format-3
 * fingerprints. Defaults invoke the REAL pinned matcher, REAL pinned geometry
 * estimator and the unchanged scorer; test seams are explicit overrides.
 */
export async function matchSide(
  query: SiftFingerprint,
  reference: SiftFingerprint,
  options: SideMatcherOptions = {},
): Promise<SideMatchResult> {
  const ownedQuery = snapshotFingerprint(query)
  let ownedReference: SiftFingerprint
  try {
    ownedReference = snapshotFingerprint(reference)
  } catch (error) {
    wipeFingerprint(ownedQuery)
    throw error
  }
  let referencePoints: MutablePoint[] | null = null
  let queryPoints: MutablePoint[] | null = null
  try {
    checkGate(options)
    const matchCorrespondence = options.matchCorrespondence ?? matchDescriptors
    const matcherFactory = options.matcherFactory ?? defaultMatcherFactory(options.signal)
    let correspondence: CorrespondenceResult
    try {
      correspondence = await matchCorrespondence(ownedQuery, ownedReference, {
        matcherFactory,
        signal: options.signal,
      })
    } catch (error) {
      throw mapCorrespondenceError(error, options.signal)
    }
    checkGate(options)

    const uniqueMatches = correspondence.diagnostics.uniqueMatches
    if (correspondence.diagnostics.noEvidence !== null || correspondence.pairs.length < MIN_GEOMETRY_PAIRS) {
      return noGeometryResult(correspondence, failureForNoEvidence(correspondence.diagnostics.noEvidence))
    }

    // DOUBLE original pixel mapping from the OWNED snapshots, in pair order.
    referencePoints = correspondence.pairs.map((pair) => pointFor(ownedReference, keypointAt(ownedReference, pair.referenceIndex)))
    queryPoints = correspondence.pairs.map((pair) => pointFor(ownedQuery, keypointAt(ownedQuery, pair.queryIndex)))
    checkGate(options)

    const estimate = options.estimateGeometry ?? estimateAndEvaluateGeometry
    let geometry: GeometryEvaluationResult
    try {
      geometry = await estimate({
        referencePoints,
        queryPoints,
        referenceWidthPx: ownedReference.canonicalWidthPx,
        referenceHeightPx: ownedReference.canonicalHeightPx,
        signal: options.signal,
      })
    } catch (error) {
      throw mapGeometryError(error, options.signal)
    }
    checkGate(options)

    let scoring: ReturnType<typeof evaluateSide>
    try {
      scoring = evaluateSide({
        query: ownedQuery,
        reference: ownedReference,
        pairs: correspondence.pairs,
        uniqueMatches,
        geometry,
      })
    } catch (error) {
      throw mapScoringError(error)
    }
    checkGate(options)

    return {
      correspondence: copyCorrespondence(correspondence),
      geometry: copyGeometry(geometry),
      signals: scoring.signals,
      report: scoring.report,
      diagnostics: {
        failure: geometry.failure,
        geometryAttempted: true,
        geometryFound: geometry.found,
        geometryAccepted: geometry.accepted,
        uniqueMatches,
        inliers: geometry.inlierIndices.length,
        medianErrorPx: geometry.medianErrorPx,
        referenceCoverage: geometry.referenceCoverage,
        supportAreaPx2: geometry.supportAreaPx2,
        supportEdgeRatio: geometry.supportEdgeRatio,
      },
    }
  } finally {
    wipeFingerprint(ownedQuery)
    wipeFingerprint(ownedReference)
    wipePoints(referencePoints)
    wipePoints(queryPoints)
  }
}
