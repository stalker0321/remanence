/**
 * T337 — real pinned-OpenCV homography ESTIMATOR bridge (technical only).
 *
 * Consumes matched reference/query PIXEL points in correspondence order and
 * returns COPIED H/mask observations compatible with the accepted T333 pure
 * evaluator (`geometryEvaluation.ts`). Exact native call, proven by T335:
 *
 *   cv.setRNGSeed(42)
 *   cv.findHomography(refNx1CV_32FC2, queryNx1CV_32FC2,
 *                     USAC_MAGSAC(38), 4.0, mask, 3000, 0.999)
 *
 * No estimator fallback, no parameter dropping, no reordering (H maps
 * reference→query). Points enter the estimator as FLOAT32 (native MatOfPoint2f
 * precision) but callers must keep their original DOUBLE pixel values for
 * evaluation — this module never rescales/reinterprets them.
 *
 * Failure observations are preserved, not "repaired": null/empty H becomes an
 * empty observation (T333 → HOMOGRAPHY_EMPTY); wrong shape/type/unreadable
 * values and wrong mask shape/type/read become truthful observations so the
 * native-order evaluator refuses them. Only execution/contract failures throw
 * a typed redacted error.
 *
 * OWNERSHIP: each owned Mat is registered immediately after construction; all
 * exposed f32/f64/u8 buffers are wiped before every delete, and every handle is
 * attempted even when an earlier wipe/delete throws. No native handle escapes.
 */

import type { RawCv } from './opencvRuntime'
import type { GeometryHomographyObservation, GeometryMaskObservation } from './geometryEvaluation'

/** Native estimator parameters (source provenance, not policy/calibration). */
export const GEOMETRY_NATIVE_PARAMETERS = Object.freeze({
  seed: 42,
  ransacReprojectionThresholdPx: 4.0,
  maxIterations: 3000,
  confidence: 0.999,
  usacMagsac: 38,
})

export const GEOMETRY_MIN_ESTIMATOR_POINTS = 4
export const GEOMETRY_MAX_POINTS = 1500

export interface GeometryObservations {
  readonly homography: GeometryHomographyObservation
  readonly mask: GeometryMaskObservation
}

export type GeometryBridgeErrorCode = 'input' | 'unavailable' | 'native-contract' | 'internal'

/** Bounded, redacted bridge refusal. Never carries point/feature data. */
export class GeometryBridgeError extends Error {
  constructor(readonly code: GeometryBridgeErrorCode) {
    super(code)
    this.name = 'GeometryBridgeError'
  }
}

interface RawMatHandle {
  rows: number
  cols: number
  data: Uint8Array
  data32F: Float32Array
  data64F?: Float64Array
  type(): number
  empty?(): boolean
  total?(): number
  delete(): void
}

interface RawHomographyCv extends RawCv {
  readonly USAC_MAGSAC: number
  readonly CV_64F: number
  setRNGSeed(seed: number): void
  findHomography(
    src: unknown,
    dst: unknown,
    method: number,
    ransacReprojectionThreshold: number,
    mask: unknown,
    maxIters: number,
    confidence: number,
  ): unknown
}

interface OwnedMat {
  readonly mat: RawMatHandle
  readonly wipe: 'f32' | 'f64' | 'u8'
}

function wipeExposed(owned: OwnedMat): void {
  try {
    const view =
      owned.wipe === 'f32' ? owned.mat.data32F : owned.wipe === 'f64' ? owned.mat.data64F : owned.mat.data
    if (view && typeof view.fill === 'function') view.fill(0)
  } catch {
    /* wiping is best-effort; delete below must still run */
  }
}

function cleanupAll(owned: readonly OwnedMat[]): unknown {
  let failure: unknown = null
  for (let index = owned.length - 1; index >= 0; index--) {
    const entry = owned[index]!
    try {
      wipeExposed(entry)
      entry.mat.delete()
    } catch (error) {
      if (failure === null) failure = error
    }
  }
  return failure
}

function readNumber(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new GeometryBridgeError('native-contract')
  }
  return value
}

/**
 * Leaf runtime bridge over an already-loaded pinned module. Synchronous: the
 * seed and estimator call are contiguous with no await/reentrant hook.
 */
export function estimateHomographyObservations(
  cvInput: RawCv,
  referencePoints: readonly { readonly x: number; readonly y: number }[],
  queryPoints: readonly { readonly x: number; readonly y: number }[],
): GeometryObservations {
  const pairCount = referencePoints.length
  if (queryPoints.length !== pairCount) throw new GeometryBridgeError('input')
  if (pairCount < GEOMETRY_MIN_ESTIMATOR_POINTS || pairCount > GEOMETRY_MAX_POINTS) {
    throw new GeometryBridgeError('input')
  }
  for (let index = 0; index < pairCount; index++) {
    const ref = referencePoints[index]!
    const query = queryPoints[index]!
    if (
      !Number.isFinite(ref.x) ||
      !Number.isFinite(ref.y) ||
      !Number.isFinite(query.x) ||
      !Number.isFinite(query.y)
    ) {
      throw new GeometryBridgeError('input')
    }
  }

  const cv = cvInput as unknown as RawHomographyCv
  if (
    typeof cv.findHomography !== 'function' ||
    typeof cv.setRNGSeed !== 'function' ||
    typeof cv.Mat !== 'function' ||
    typeof cv.CV_32FC2 !== 'number' ||
    typeof cv.USAC_MAGSAC !== 'number'
  ) {
    throw new GeometryBridgeError('unavailable')
  }

  const owned: OwnedMat[] = []
  const own = (mat: RawMatHandle, wipe: OwnedMat['wipe']): RawMatHandle => {
    owned.push({ mat, wipe })
    return mat
  }
  const asMat = (mat: unknown): RawMatHandle => {
    if (mat === null || typeof mat !== 'object') throw new GeometryBridgeError('native-contract')
    return mat as RawMatHandle
  }

  let result: GeometryObservations | null = null
  let primary: unknown = null
  try {
    const buildPoints = (points: readonly { readonly x: number; readonly y: number }[]): RawMatHandle => {
      const mat = own(asMat(new cv.Mat(pairCount, 1, cv.CV_32FC2)), 'f32')
      const view = mat.data32F
      if (!(view instanceof Float32Array) || view.length !== pairCount * 2) {
        throw new GeometryBridgeError('native-contract')
      }
      for (let index = 0; index < pairCount; index++) {
        view[2 * index] = Math.fround(points[index]!.x)
        view[2 * index + 1] = Math.fround(points[index]!.y)
      }
      return mat
    }

    const referenceMat = buildPoints(referencePoints)
    const queryMat = buildPoints(queryPoints)
    const mask = own(asMat(new cv.Mat()), 'u8')

    // CRITICAL SECTION: contiguous synchronous seed + exact seven-argument call.
    cv.setRNGSeed(GEOMETRY_NATIVE_PARAMETERS.seed)
    const homographyRaw = cv.findHomography(
      referenceMat,
      queryMat,
      cv.USAC_MAGSAC,
      GEOMETRY_NATIVE_PARAMETERS.ransacReprojectionThresholdPx,
      mask,
      GEOMETRY_NATIVE_PARAMETERS.maxIterations,
      GEOMETRY_NATIVE_PARAMETERS.confidence,
    )

    if (homographyRaw === null || homographyRaw === undefined) {
      // Null H is a truthful empty observation (native HOMOGRAPHY_EMPTY path).
      result = {
        homography: { empty: true, rows: 0, cols: 0, type: 0, values: null },
        mask: readMask(mask, pairCount),
      }
    } else {
      const homography = own(asMat(homographyRaw), 'f64')
      result = { homography: readHomography(homography), mask: readMask(mask, pairCount) }
    }
  } catch (error) {
    primary = error
  }
  const cleanupFailure = cleanupAll(owned)
  if (primary !== null) {
    if (primary instanceof GeometryBridgeError) throw primary
    throw new GeometryBridgeError('internal')
  }
  if (cleanupFailure !== null) throw new GeometryBridgeError('internal')
  if (result === null) throw new GeometryBridgeError('internal')
  return result
}

function readHomography(mat: RawMatHandle): GeometryHomographyObservation {
  let empty: boolean
  let rows: number
  let cols: number
  let type: number
  try {
    empty = typeof mat.empty === 'function' ? mat.empty() : false
    rows = readNumber(mat.rows)
    cols = readNumber(mat.cols)
    type = readNumber(mat.type())
  } catch (error) {
    if (error instanceof GeometryBridgeError) throw error
    throw new GeometryBridgeError('native-contract')
  }
  if (empty) return { empty: true, rows, cols, type, values: null }
  // Copy at most the nine 3x3 cells; do not trust arbitrary view metadata.
  let values: number[] | null = null
  if (rows === 3 && cols === 3 && type === 6) {
    try {
      const view = mat.data64F
      if (view instanceof Float64Array && view.length >= 9) values = Array.from(view.slice(0, 9))
    } catch {
      values = null
    }
  }
  return { empty: false, rows, cols, type, values }
}

function readMask(mat: RawMatHandle, pairCount: number): GeometryMaskObservation {
  let empty: boolean
  let rows: number
  let cols: number
  let type: number
  let total: number
  try {
    empty = typeof mat.empty === 'function' ? mat.empty() : false
    rows = readNumber(mat.rows)
    cols = readNumber(mat.cols)
    type = readNumber(mat.type())
    total = typeof mat.total === 'function' ? readNumber(mat.total()) : rows * cols
  } catch (error) {
    if (error instanceof GeometryBridgeError) throw error
    throw new GeometryBridgeError('native-contract')
  }
  const shapeOk = (rows === pairCount && cols === 1) || (rows === 1 && cols === pairCount)
  // Never copy an unbounded native view because malformed metadata claims it.
  let bytes: number[] | null = null
  if (!empty && type === 0 && total === pairCount && shapeOk) {
    try {
      const view = mat.data
      if (view instanceof Uint8Array && view.length === pairCount) bytes = Array.from(view)
    } catch {
      bytes = null
    }
  }
  return { empty, rows, cols, type, bytes }
}
