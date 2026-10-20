/**
 * T345 — real pinned-OpenCV CAPTURE-QUALITY meter bridge (technical only).
 *
 * Mirrors the accepted Android `CaptureQualityMeter.measure` op sequence on a
 * plain RGBA byte frame. Proven by the accepted T344 runtime probe:
 *
 *   new Mat(h, w, CV_8UC4) <- rgba
 *   cvtColor(rgba, gray, COLOR_RGBA2GRAY)
 *   Laplacian(gray, lap, CV_64F)              // 3-arg native default
 *   lap.mul(lap, 1.0)                         // returns a NEW CV_64F Mat
 *   mean(lap), mean(squared)                  // Array-subclass Scalar, [0]
 *   variance = meanSq - mean * mean           // no clamp
 *   exposure from the ACTUAL gray bytes: <=40 near-black, >=252 clipped
 *   threshold(gray, mask, 245, 255, THRESH_BINARY)     // strictly > 245
 *   connectedComponentsWithStats(mask, labels, stats, centroids)  // 4-arg 8/CV_32S
 *   largest foreground area from stats[label * cols + CC_STAT_AREA]
 *
 * The native calls are contiguous and synchronous. This module returns ONLY the
 * four copied scalar signals; it never returns/leaks feature buffers or native
 * views. Every owned Mat is registered immediately (including the `mul`
 * return), each exposed buffer is wiped before its delete, and every handle is
 * attempted even after an earlier wipe/delete failure.
 */

import { WARP_MAX_PIXELS } from './warpGeometry'

export const CAPTURE_QUALITY_NATIVE = Object.freeze({
  nearBlackCutoff: 40,
  clippedWhiteCutoff: 252,
  glareThreshold: 245,
  glareMaxValue: 255,
  /** `CC_STAT_AREA` column in the native stats matrix. */
  statsAreaColumn: 4,
  statsColumns: 5,
} as const)

export type CaptureQualityErrorCode = 'contract' | 'unavailable' | 'cancelled' | 'stale' | 'internal'

/** Redacted, bounded refusal (never carries pixel/feature data). */
export class CaptureQualityError extends Error {
  constructor(readonly code: CaptureQualityErrorCode) {
    super(code)
    this.name = 'CaptureQualityError'
  }
}

export interface CaptureQualitySignals {
  readonly laplacianVariance: number
  readonly nearBlackFraction: number
  readonly clippedWhiteFraction: number
  readonly largestGlareFraction: number
}

/** Narrow, locally validated raw-interface (does NOT widen the shared `RawCv`). */
export interface QualityRawMat {
  rows: number
  cols: number
  data?: Uint8Array
  data64F?: Float64Array
  data32S?: Int32Array
  data32F?: Float32Array
  type(): number
  empty?(): boolean
  total?(): number
  mul?(other: QualityRawMat, scale: number): QualityRawMat
  delete(): void
}

export interface QualityMeanResult {
  readonly [index: number]: number
  readonly val?: ArrayLike<number>
}

export interface QualityRawCv {
  Mat: new (rows?: number, cols?: number, type?: number) => QualityRawMat
  CV_8UC1: number
  CV_8UC4: number
  CV_64F: number
  CV_32S: number
  COLOR_RGBA2GRAY: number
  THRESH_BINARY: number
  CC_STAT_AREA: number
  cvtColor(src: QualityRawMat, dst: QualityRawMat, code: number): void
  Laplacian(src: QualityRawMat, dst: QualityRawMat, ddepth: number): void
  mean(src: QualityRawMat): QualityMeanResult
  threshold(src: QualityRawMat, dst: QualityRawMat, thresh: number, maxval: number, type: number): number
  connectedComponentsWithStats(
    src: QualityRawMat,
    labels: QualityRawMat,
    stats: QualityRawMat,
    centroids: QualityRawMat,
  ): number
}

export interface CaptureQualityDimensions {
  readonly width: number
  readonly height: number
}

/** Structural dimension/overflow validation using the existing warp pixel budget. */
export function validateCaptureQualityDimensions(width: number, height: number): CaptureQualityDimensions {
  if (typeof width !== 'number' || typeof height !== 'number') throw new CaptureQualityError('contract')
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height)) throw new CaptureQualityError('contract')
  if (width < 1 || height < 1) throw new CaptureQualityError('contract')
  if (width * height > WARP_MAX_PIXELS) throw new CaptureQualityError('contract')
  return { width, height }
}

function requireSymbols(cv: QualityRawCv): void {
  const available =
    typeof cv.Mat === 'function' &&
    typeof cv.cvtColor === 'function' &&
    typeof cv.Laplacian === 'function' &&
    typeof cv.mean === 'function' &&
    typeof cv.threshold === 'function' &&
    typeof cv.connectedComponentsWithStats === 'function' &&
    typeof cv.CV_8UC1 === 'number' &&
    typeof cv.CV_8UC4 === 'number' &&
    typeof cv.CV_64F === 'number' &&
    typeof cv.CV_32S === 'number' &&
    typeof cv.COLOR_RGBA2GRAY === 'number' &&
    typeof cv.THRESH_BINARY === 'number' &&
    typeof cv.CC_STAT_AREA === 'number'
  if (!available) throw new CaptureQualityError('unavailable')
}

interface OwnedMat {
  readonly mat: QualityRawMat
}

/**
 * Wipes every ACTUAL exposed backing view, each in its own try: a malformed
 * view getter must not escape or stop the other views (and thus the delete).
 */
function wipeExposed(entry: OwnedMat): void {
  try {
    const view = entry.mat.data
    if (view && typeof view.fill === 'function') view.fill(0)
  } catch {
    /* unavailable view; continue */
  }
  try {
    const view = entry.mat.data64F
    if (view && typeof view.fill === 'function') view.fill(0)
  } catch {
    /* unavailable view; continue */
  }
  try {
    const view = entry.mat.data32S
    if (view && typeof view.fill === 'function') view.fill(0)
  } catch {
    /* unavailable view; continue */
  }
  try {
    const view = entry.mat.data32F
    if (view && typeof view.fill === 'function') view.fill(0)
  } catch {
    /* unavailable view; continue */
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

function readShellNumber(mat: QualityRawMat, key: 'rows' | 'cols'): number {
  let value: unknown
  try {
    value = mat[key]
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new CaptureQualityError('contract')
  }
  return value
}

function readType(mat: QualityRawMat): number {
  if (typeof mat.type !== 'function') throw new CaptureQualityError('contract')
  let value: unknown
  try {
    value = mat.type()
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new CaptureQualityError('contract')
  return value
}

function readUint8View(mat: QualityRawMat, expectedLength: number): Uint8Array {
  let view: unknown
  try {
    view = mat.data
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (!(view instanceof Uint8Array) || view.length !== expectedLength) throw new CaptureQualityError('contract')
  return view
}

function readFloat64View(mat: QualityRawMat, expectedLength: number): Float64Array {
  let view: unknown
  try {
    view = mat.data64F
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (!(view instanceof Float64Array) || view.length !== expectedLength) throw new CaptureQualityError('contract')
  return view
}

function readInt32View(mat: QualityRawMat, expectedLength: number): Int32Array {
  let view: unknown
  try {
    view = mat.data32S
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (!(view instanceof Int32Array) || view.length !== expectedLength) throw new CaptureQualityError('contract')
  return view
}

function readScalarValue(scalar: QualityMeanResult): number {
  let value: unknown
  try {
    const withVal = scalar.val
    if (withVal && typeof withVal.length === 'number') value = withVal[0]
    else value = scalar[0]
  } catch {
    throw new CaptureQualityError('contract')
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new CaptureQualityError('contract')
  return value
}

/**
 * Synchronous leaf over an already-loaded pinned runtime.
 * Returns only the four copied scalar signals.
 */
export function measureCaptureQualitySignals(
  cv: QualityRawCv,
  rgba: Uint8Array,
  width: number,
  height: number,
): CaptureQualitySignals {
  const dims = validateCaptureQualityDimensions(width, height)
  if (!(rgba instanceof Uint8Array) || rgba.length !== dims.width * dims.height * 4) {
    throw new CaptureQualityError('contract')
  }
  requireSymbols(cv)

  const owned: OwnedMat[] = []
  const own = (mat: QualityRawMat): QualityRawMat => {
    owned.push({ mat })
    return mat
  }
  const asMat = (value: unknown): QualityRawMat => {
    if (value === null || typeof value !== 'object') throw new CaptureQualityError('contract')
    return value as QualityRawMat
  }

  let result: CaptureQualitySignals | null = null
  let primary: unknown = null
  try {
    const rgbaMat = own(asMat(new cv.Mat(dims.height, dims.width, cv.CV_8UC4)))
    const rgbaView = readUint8View(rgbaMat, rgba.length)
    rgbaView.set(rgba)

    const gray = own(asMat(new cv.Mat()))
    cv.cvtColor(rgbaMat, gray, cv.COLOR_RGBA2GRAY)
    if (readShellNumber(gray, 'rows') !== dims.height || readShellNumber(gray, 'cols') !== dims.width) {
      throw new CaptureQualityError('contract')
    }
    if (readType(gray) !== cv.CV_8UC1) throw new CaptureQualityError('contract')
    const grayBytes = readUint8View(gray, dims.width * dims.height)

    const lap = own(asMat(new cv.Mat()))
    cv.Laplacian(gray, lap, cv.CV_64F)
    if (readShellNumber(lap, 'rows') !== dims.height || readShellNumber(lap, 'cols') !== dims.width) {
      throw new CaptureQualityError('contract')
    }
    if (readType(lap) !== cv.CV_64F) throw new CaptureQualityError('contract')
    readFloat64View(lap, dims.width * dims.height)

    if (typeof lap.mul !== 'function') throw new CaptureQualityError('unavailable')
    const squaredRaw = lap.mul(lap, 1.0)
    const squared = own(asMat(squaredRaw))
    if (readShellNumber(squared, 'rows') !== dims.height || readShellNumber(squared, 'cols') !== dims.width) {
      throw new CaptureQualityError('contract')
    }
    if (readType(squared) !== cv.CV_64F) throw new CaptureQualityError('contract')
    readFloat64View(squared, dims.width * dims.height)

    const mean = readScalarValue(cv.mean(lap))
    const meanSq = readScalarValue(cv.mean(squared))
    const laplacianVariance = meanSq - mean * mean
    if (!Number.isFinite(laplacianVariance)) throw new CaptureQualityError('contract')

    let nearBlack = 0
    let clippedWhite = 0
    for (let index = 0; index < grayBytes.length; index++) {
      const luminance = grayBytes[index]!
      if (luminance <= CAPTURE_QUALITY_NATIVE.nearBlackCutoff) nearBlack += 1
      if (luminance >= CAPTURE_QUALITY_NATIVE.clippedWhiteCutoff) clippedWhite += 1
    }

    const mask = own(asMat(new cv.Mat()))
    cv.threshold(
      gray,
      mask,
      CAPTURE_QUALITY_NATIVE.glareThreshold,
      CAPTURE_QUALITY_NATIVE.glareMaxValue,
      cv.THRESH_BINARY,
    )
    if (readShellNumber(mask, 'rows') !== dims.height || readShellNumber(mask, 'cols') !== dims.width) {
      throw new CaptureQualityError('contract')
    }
    if (readType(mask) !== cv.CV_8UC1) throw new CaptureQualityError('contract')
    readUint8View(mask, dims.width * dims.height)

    const labels = own(asMat(new cv.Mat()))
    const stats = own(asMat(new cv.Mat()))
    const centroids = own(asMat(new cv.Mat()))
    let labelCount: unknown
    try {
      labelCount = cv.connectedComponentsWithStats(mask, labels, stats, centroids)
    } catch (error) {
      throw error instanceof CaptureQualityError ? error : new CaptureQualityError('internal')
    }
    if (typeof labelCount !== 'number' || !Number.isSafeInteger(labelCount) || labelCount < 1) {
      throw new CaptureQualityError('contract')
    }
    if (readShellNumber(labels, 'rows') !== dims.height || readShellNumber(labels, 'cols') !== dims.width) {
      throw new CaptureQualityError('contract')
    }
    if (readType(labels) !== cv.CV_32S) throw new CaptureQualityError('contract')
    readInt32View(labels, dims.width * dims.height)

    if (readShellNumber(stats, 'rows') !== labelCount) throw new CaptureQualityError('contract')
    const statsColumns = readShellNumber(stats, 'cols')
    if (statsColumns !== CAPTURE_QUALITY_NATIVE.statsColumns) throw new CaptureQualityError('contract')
    if (readType(stats) !== cv.CV_32S) throw new CaptureQualityError('contract')
    const statsView = readInt32View(stats, labelCount * statsColumns)

    if (readShellNumber(centroids, 'rows') !== labelCount || readShellNumber(centroids, 'cols') !== 2) {
      throw new CaptureQualityError('contract')
    }
    if (readType(centroids) !== cv.CV_64F) throw new CaptureQualityError('contract')
    readFloat64View(centroids, labelCount * 2)

    let largestGlare = 0
    for (let label = 1; label < labelCount; label++) {
      const area = statsView[label * statsColumns + cv.CC_STAT_AREA]!
      if (!Number.isInteger(area) || area < 0) throw new CaptureQualityError('contract')
      if (area > largestGlare) largestGlare = area
    }

    const total = dims.width * dims.height
    result = {
      laplacianVariance,
      nearBlackFraction: nearBlack / total,
      clippedWhiteFraction: clippedWhite / total,
      largestGlareFraction: largestGlare / total,
    }
  } catch (error) {
    primary = error
  }
  const cleanupFailure = cleanupAll(owned)
  if (primary !== null) {
    if (primary instanceof CaptureQualityError) throw primary
    throw new CaptureQualityError('internal')
  }
  if (cleanupFailure !== null) throw new CaptureQualityError('internal')
  if (result === null) throw new CaptureQualityError('internal')
  return result
}
