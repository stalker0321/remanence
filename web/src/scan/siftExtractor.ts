/**
 * T312 — genuine local browser SIFT/RootSIFT-input extractor.
 *
 * Starting from an already-canonical RGBA pixel frame, this runs the REAL
 * pinned OpenCV.js SIFT detector and descriptor computation (via `CvRuntime`),
 * reproduces the Android `SiftRootSiftFingerprintExtractor` selection/rounding
 * policy, and returns the accepted format-3 domain value
 * (`SiftFingerprint`, serialized by the unchanged T308 codec).
 *
 * This is NOT camera capture, postcard localization/warp, RootSIFT derivation,
 * matching, quality policy or field-accuracy parity. The frame is trusted to be
 * an already-canonical image; this module makes no admission claim about it.
 *
 * Descriptor rows are RAW quantized SIFT (no RootSIFT); an all-zero row is
 * storage-valid. No pixel data is placed into results, errors or logs.
 */

import {
  DESCRIPTOR_BYTES,
  MAX_KEYPOINTS,
  SIFT_FINGERPRINT_PROFILE_ID,
  type SiftFingerprint,
  type SiftFingerprintKeypoint,
} from './siftFingerprint'
import { loadOpenCvRuntime } from './opencvRuntime'
import type { CvKeyPoint, CvMat, CvRuntime } from './opencvPort'

const SIFT_NFEATURES = 0
const SIFT_OCTAVE_LAYERS = 3
const SIFT_CONTRAST_THRESHOLD = 0.018
const SIFT_EDGE_THRESHOLD = 12.0
const SIFT_SIGMA = 1.6

const GRID_SIZE = 6
const MAX_PER_CELL = 45
const MAX_DETECTED_KEYPOINTS = 100_000
const MAX_CANONICAL_DIMENSION_PX = 100_000
const MAX_CANONICAL_WARP_PIXELS = 16_000_000

const MICRO_UNITS = 1_000_000
const RESPONSE_SCALE = 1_000_000
const CENTIDEGREES_PER_DEGREE = 100
const MAX_ANGLE_CENTI_DEGREES = 35_999
const MIN_OCTAVE = -8
const MAX_OCTAVE = 8

const HASH_SIZE = 8
const RGBA_BYTES_PER_PIXEL = 4

export type SiftExtractionErrorCode =
  | 'dimensions'
  | 'pixels'
  | 'unavailable'
  | 'no-features'
  | 'shape'
  | 'aborted'
  | 'internal'

/** Bounded, redacted extraction refusal. Never carries pixel/descriptor data. */
export class SiftExtractionError extends Error {
  constructor(readonly code: SiftExtractionErrorCode) {
    super(code)
    this.name = 'SiftExtractionError'
  }
}

function refuse(code: SiftExtractionErrorCode): SiftExtractionError {
  return new SiftExtractionError(code)
}

/** Explicit already-canonical RGBA frame (4 bytes per pixel, row-major). */
export interface CanonicalRgbaFrame {
  readonly pixels: Uint8Array
  readonly width: number
  readonly height: number
}

/** A copied native detection used by selection/quantization. */
export interface SiftCandidate {
  readonly xPx: number
  readonly yPx: number
  readonly sizePx: number
  readonly angleDegrees: number
  readonly response: number
  readonly encodedOctave: number
  readonly sourceIndex: number
}

export interface SelectedSiftCandidate {
  readonly candidate: SiftCandidate
  readonly cellX: number
  readonly cellY: number
}

export interface ExtractSiftOptions {
  readonly signal?: AbortSignal
  /** Test seam only; the production path always loads the real pinned runtime. */
  readonly runtime?: CvRuntime
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) throw refuse('aborted')
}

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}

/**
 * Validates the frame and returns an owned copy of its pixels, synchronously,
 * before any await or native allocation. `byteLength` is derived by
 * multiplication only after the dimension/pixel-budget guards pass.
 */
export function validateAndOwnFrame(frame: CanonicalRgbaFrame): Uint8Array {
  if (!frame || typeof frame !== 'object') throw refuse('dimensions')
  const { width, height } = frame
  if (!isInteger(width) || width < 1 || width > MAX_CANONICAL_DIMENSION_PX) throw refuse('dimensions')
  if (!isInteger(height) || height < 1 || height > MAX_CANONICAL_DIMENSION_PX) throw refuse('dimensions')
  const pixelCount = width * height
  if (!Number.isSafeInteger(pixelCount) || pixelCount > MAX_CANONICAL_WARP_PIXELS) throw refuse('dimensions')
  const expected = pixelCount * RGBA_BYTES_PER_PIXEL
  const pixels = frame.pixels
  if (!(pixels instanceof Uint8Array) || pixels.length !== expected) throw refuse('pixels')
  const owned = new Uint8Array(expected)
  owned.set(pixels)
  return owned
}

/** Exact Android grayscale: integer floor((299R + 587G + 114B) / 1000), alpha ignored. */
export function toGrayRgba(rgba: Uint8Array, width: number, height: number): Uint8Array {
  const gray = new Uint8Array(width * height)
  for (let index = 0; index < gray.length; index++) {
    const base = index * RGBA_BYTES_PER_PIXEL
    const red = rgba[base]!
    const green = rgba[base + 1]!
    const blue = rgba[base + 2]!
    gray[index] = ((299 * red + 587 * green + 114 * blue) / 1000) | 0
  }
  return gray
}

/** Signed low byte of the native encoded octave, matching Android `decodeOctave`. */
export function decodeOctave(encoded: number): number {
  const lowByte = encoded & 0xff
  return lowByte >= 128 ? lowByte - 256 : lowByte
}

export function candidateFromKeyPoint(point: CvKeyPoint, sourceIndex: number): SiftCandidate {
  return {
    xPx: point.pt.x,
    yPx: point.pt.y,
    sizePx: point.size,
    angleDegrees: point.angle,
    response: point.response,
    encodedOctave: decodeOctave(point.octave),
    sourceIndex,
  }
}

function cell(normalized: number, count: number): number {
  return Math.min(count - 1, Math.floor(normalized * count))
}

function compareDescending(left: number, right: number): number {
  return left < right ? 1 : left > right ? -1 : 0
}

function compareAscending(left: number, right: number): number {
  return left < right ? -1 : left > right ? 1 : 0
}

/** Full deterministic candidate order: response DESC then geometry/index ASC. */
export function candidateComparator(left: SelectedSiftCandidate, right: SelectedSiftCandidate): number {
  return (
    compareDescending(left.candidate.response, right.candidate.response) ||
    compareAscending(left.cellY, right.cellY) ||
    compareAscending(left.cellX, right.cellX) ||
    compareAscending(left.candidate.xPx, right.candidate.xPx) ||
    compareAscending(left.candidate.yPx, right.candidate.yPx) ||
    compareAscending(left.candidate.sizePx, right.candidate.sizePx) ||
    compareAscending(left.candidate.angleDegrees, right.candidate.angleDegrees) ||
    compareAscending(left.candidate.encodedOctave, right.candidate.encodedOctave) ||
    compareAscending(left.candidate.sourceIndex, right.candidate.sourceIndex)
  )
}

interface PreparedCandidate {
  readonly candidate: SiftCandidate
  readonly cellX: number
  readonly cellY: number
}

/**
 * Validates, spatially distributes and caps copied detections exactly like the
 * Android reference: 6x6 half-open grid, per-cell top 45, deterministic global
 * top 1500. Selection happens BEFORE descriptor computation.
 */
export function selectCandidates(
  width: number,
  height: number,
  candidates: readonly SiftCandidate[],
): SelectedSiftCandidate[] {
  if (!isInteger(width) || width < 1 || width > MAX_CANONICAL_DIMENSION_PX) throw refuse('shape')
  if (!isInteger(height) || height < 1 || height > MAX_CANONICAL_DIMENSION_PX) throw refuse('shape')
  if (candidates.length > MAX_DETECTED_KEYPOINTS) throw refuse('shape')
  if (candidates.length === 0) throw refuse('no-features')

  const prepared: PreparedCandidate[] = candidates.map((candidate) => {
    if (!isInteger(candidate.sourceIndex) || candidate.sourceIndex < 0) throw refuse('shape')
    if (!Number.isFinite(candidate.xPx) || !Number.isFinite(candidate.yPx)) throw refuse('shape')
    if (candidate.xPx < 0 || candidate.xPx > width || candidate.yPx < 0 || candidate.yPx > height) throw refuse('shape')
    if (!Number.isFinite(candidate.sizePx) || candidate.sizePx <= 0) throw refuse('shape')
    const scale = candidate.sizePx / width
    if (!Number.isFinite(scale) || scale <= 0) throw refuse('shape')
    if (!Number.isFinite(candidate.angleDegrees) || candidate.angleDegrees < 0 || candidate.angleDegrees >= 360) throw refuse('shape')
    if (!Number.isFinite(candidate.response) || candidate.response < 0) throw refuse('shape')
    if (!isInteger(candidate.encodedOctave) || candidate.encodedOctave < MIN_OCTAVE || candidate.encodedOctave > MAX_OCTAVE) throw refuse('shape')
    return {
      candidate,
      cellX: cell(candidate.xPx / width, GRID_SIZE),
      cellY: cell(candidate.yPx / height, GRID_SIZE),
    }
  })

  const cells: PreparedCandidate[][] = Array.from({ length: GRID_SIZE * GRID_SIZE }, () => [])
  for (const item of prepared) cells[item.cellY * GRID_SIZE + item.cellX]!.push(item)
  const perCell: PreparedCandidate[] = []
  for (const cellCandidates of cells) {
    cellCandidates.sort(candidateComparator)
    for (let index = 0; index < Math.min(MAX_PER_CELL, cellCandidates.length); index++) perCell.push(cellCandidates[index]!)
  }
  perCell.sort(candidateComparator)
  return perCell.slice(0, MAX_KEYPOINTS).map((item) => ({ candidate: item.candidate, cellX: item.cellX, cellY: item.cellY }))
}

/** Ties-to-even rounding, matching Kotlin `Math.rint`. */
export function rint(value: number): number {
  const floor = Math.floor(value)
  const fraction = value - floor
  if (fraction < 0.5) return floor
  if (fraction > 0.5) return floor + 1
  return floor % 2 === 0 ? floor : floor + 1
}

function quantizeUnit(value: number): number {
  if (!Number.isFinite(value) || value < 0 || value > 1) throw refuse('shape')
  return Math.min(MICRO_UNITS, Math.max(0, rint(value * MICRO_UNITS)))
}

function quantizeClampedUnit(value: number): number {
  if (!Number.isFinite(value) || value < 0) throw refuse('shape')
  if (value >= 1) return MICRO_UNITS
  return Math.min(MICRO_UNITS, Math.max(0, rint(value * MICRO_UNITS)))
}

/** Android metadata mirror: width-normalized micro-units, ties-to-even, clamping. */
export function quantizeKeypoint(candidate: SiftCandidate, width: number, height: number): SiftFingerprintKeypoint {
  const xMicro = quantizeUnit(candidate.xPx / width)
  const yMicro = quantizeUnit(candidate.yPx / height)
  const scaleMicro = quantizeClampedUnit(candidate.sizePx / width)
  const angle = Math.min(MAX_ANGLE_CENTI_DEGREES, Math.max(0, rint(candidate.angleDegrees * CENTIDEGREES_PER_DEGREE)))
  const responseScaled = candidate.response * RESPONSE_SCALE
  if (!Number.isFinite(responseScaled)) throw refuse('shape')
  const responseQuantized = Math.min(MICRO_UNITS, Math.max(0, rint(responseScaled)))
  if (candidate.encodedOctave < MIN_OCTAVE || candidate.encodedOctave > MAX_OCTAVE) throw refuse('shape')
  return { xMicro, yMicro, scaleMicro, angleCentiDegrees: angle, responseQuantized, octave: candidate.encodedOctave }
}

/** Raw quantized SIFT row: finite values, ties-to-even, clamped to [0, 255]. */
export function quantizeRawSiftRow(values: ArrayLike<number>): Uint8Array {
  if (values.length !== DESCRIPTOR_BYTES) throw refuse('shape')
  const row = new Uint8Array(DESCRIPTOR_BYTES)
  for (let index = 0; index < DESCRIPTOR_BYTES; index++) {
    const value = values[index]!
    if (!Number.isFinite(value)) throw refuse('shape')
    row[index] = Math.min(255, Math.max(0, rint(value)))
  }
  return row
}

/**
 * Coarse hash over the 8x8 INTER_AREA resize: drop the DC term, median of the
 * 63 AC coefficients, pack 63 strict-greater bits MSB-first into a bigint.
 */
export function packCoarseHash(values: ArrayLike<number>): bigint {
  if (values.length !== HASH_SIZE * HASH_SIZE) throw refuse('shape')
  const ac = new Float64Array(HASH_SIZE * HASH_SIZE - 1)
  for (let index = 1; index < values.length; index++) {
    const value = values[index]!
    if (!Number.isFinite(value)) throw refuse('shape')
    ac[index - 1] = value
  }
  const sorted = Array.from(ac).sort((left, right) => left - right)
  const median = sorted[sorted.length >> 1]!
  let packed = 0n
  for (let index = 1; index < values.length; index++) {
    packed = (packed << 1n) | (values[index]! > median ? 1n : 0n)
  }
  return packed
}

function coarseHash64(runtime: CvRuntime, gray: CvMat, own: (mat: CvMat) => void): bigint {
  const small = runtime.resizeArea(gray, HASH_SIZE)
  own(small)
  const floatSmall = runtime.convertToFloat(small)
  own(floatSmall)
  const transformed = runtime.dct(floatSmall)
  own(transformed)
  return packCoarseHash(transformed.data32F)
}

export function hasMatcherUsableRows(rows: readonly Uint8Array[]): boolean {
  return rows.some((row) => row.some((byte) => byte !== 0))
}

/**
 * Core extraction over already-owned grayscale-ready RGBA bytes. Synchronous:
 * the real OpenCV.js SIFT/detect/compute and DCT stages are not preemptively
 * interruptible, so cancellation is only observed at stage boundaries.
 */
export function extractWithRuntime(
  runtime: CvRuntime,
  rgba: Uint8Array,
  width: number,
  height: number,
  signal?: AbortSignal,
): SiftFingerprint {
  throwIfAborted(signal)
  const native: Array<() => void> = []
  const ownMat = (mat: CvMat): void => {
    native.push(() => mat.delete())
  }
  let sift: ReturnType<CvRuntime['createSift']> | null = null
  let grayBytes: Uint8Array | null = null
  const descriptorRows: Uint8Array[] = []
  try {
    sift = runtime.createSift({
      nfeatures: SIFT_NFEATURES,
      octaveLayers: SIFT_OCTAVE_LAYERS,
      contrastThreshold: SIFT_CONTRAST_THRESHOLD,
      edgeThreshold: SIFT_EDGE_THRESHOLD,
      sigma: SIFT_SIGMA,
    })
    native.push(() => sift?.delete())

    grayBytes = toGrayRgba(rgba, width, height)
    const gray = runtime.matFromGray(grayBytes, width, height)
    ownMat(gray)

    throwIfAborted(signal)
    const detected = sift.detect(gray)
    if (detected.length > MAX_DETECTED_KEYPOINTS) throw refuse('shape')
    throwIfAborted(signal)

    const candidates = detected.map((point, index) => candidateFromKeyPoint(point, index))
    const selected = selectCandidates(width, height, candidates)
    const selectedPoints = selected.map((item) => detected[item.candidate.sourceIndex]!)

    throwIfAborted(signal)
    const computedResult = sift.compute(gray, selectedPoints)
    const descriptors = computedResult.descriptors
    ownMat(descriptors)
    throwIfAborted(signal)

    if (
      computedResult.keypoints.length !== selected.length ||
      descriptors.rows !== selected.length ||
      descriptors.cols !== DESCRIPTOR_BYTES ||
      descriptors.type() !== runtime.CV_32F
    ) {
      throw refuse('shape')
    }

    const keypoints: SiftFingerprintKeypoint[] = []
    for (let row = 0; row < selected.length; row++) {
      const point = computedResult.keypoints[row]!
      keypoints.push(quantizeKeypoint(candidateFromKeyPoint(point, selected[row]!.candidate.sourceIndex), width, height))
      descriptorRows.push(quantizeRawSiftRow(descriptors.data32F.subarray(row * DESCRIPTOR_BYTES, (row + 1) * DESCRIPTOR_BYTES)))
    }
    if (keypoints.length === 0) throw refuse('no-features')
    if (!hasMatcherUsableRows(descriptorRows)) throw refuse('no-features')

    const coarseHash = coarseHash64(runtime, gray, ownMat)
    return {
      profileId: SIFT_FINGERPRINT_PROFILE_ID,
      canonicalWidthPx: width,
      canonicalHeightPx: height,
      coarseHash64: coarseHash,
      keypoints,
      quantizedSiftDescriptors: descriptorRows,
    }
  } catch (error) {
    for (const row of descriptorRows) row.fill(0)
    throw error
  } finally {
    if (grayBytes) grayBytes.fill(0)
    native.reverse().forEach((action) => {
      try {
        action()
      } catch {
        /* best-effort native release; never mask the primary outcome */
      }
    })
  }
}

/**
 * Genuine extraction entry point. Validates and owns the caller frame before the
 * first await, loads the pinned same-origin runtime if not injected, then runs
 * the synchronous native pipeline.
 */
export async function extractSiftFingerprint(
  frame: CanonicalRgbaFrame,
  options: ExtractSiftOptions = {},
): Promise<SiftFingerprint> {
  const owned = validateAndOwnFrame(frame)
  const signal = options.signal
  try {
    throwIfAborted(signal)
    let runtime = options.runtime
    if (!runtime) {
      try {
        runtime = await loadOpenCvRuntime(signal)
      } catch (error) {
        if (error instanceof SiftExtractionError) throw error
        throw refuse('unavailable')
      }
    }
    throwIfAborted(signal)
    return extractWithRuntime(runtime, owned, frame.width, frame.height, signal)
  } finally {
    owned.fill(0)
  }
}
