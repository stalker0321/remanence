/**
 * T345 — capture quality: real pinned measurement + PURE ADVISORY gate.
 *
 * Two independent pieces over plain data:
 *
 * 1. A synchronous native meter leaf (`createCaptureQualityMeter(cv)`) bound to
 *    an already-loaded pinned runtime for later post-warp use, plus a small
 *    async default-loader wrapper (`measureCaptureQuality`) over the shared
 *    accepted `loadOpenCvModule`. Both return ONLY the four copied scalar
 *    signals; the caller's RGBA bytes are never mutated and every owned native
 *    buffer is wiped before delete.
 *
 * 2. A pure port of `CaptureQualityGate.evaluate` with the ACTIVE FRONT
 *    calibratedM2 blur threshold (110), the frozen profile gates, orientation-
 *    normalized aspect handling, optional crop fields, deterministic native
 *    reason order, and FRONT-only/BACK-unsupported behavior.
 *
 * Warnings remain ADVISORY: the gate returns reason codes only and never
 * rejects a query by itself. This module is additive and is NOT wired into the
 * producer/query/engine/UI/Scan/grant. Decode/warp/empty-feature hard failures
 * stay in the existing producer.
 */

import { loadOpenCvModule, type RawCv } from './opencvRuntime'
import {
  CAPTURE_QUALITY_NATIVE,
  CaptureQualityError,
  measureCaptureQualitySignals,
  validateCaptureQualityDimensions,
  type CaptureQualitySignals,
  type QualityRawCv,
} from './opencvQualityPort'

export { CAPTURE_QUALITY_NATIVE, CaptureQualityError }
export type { CaptureQualityErrorCode, CaptureQualitySignals } from './opencvQualityPort'

/** Frozen ACTIVE FRONT calibratedM2 blur threshold (profile legacy 80 is NOT active). */
export const FRONT_ADMISSION_MIN_LAPLACIAN_VARIANCE = 110.0

/** Central read-only gate constants (frozen `postcard-sift-rootsift-v1`). */
export const CAPTURE_QUALITY_GATES = Object.freeze({
  minCardAreaRatio: 0.35,
  minShortEdgeAfterWarpPx: 600,
  aspectRatioMin: 1.15,
  aspectRatioMax: 2.20,
  maxNearBlackFraction: 0.25,
  maxClippedWhiteFraction: 0.2,
  maxGlareRegionFraction: 0.12,
  minRectangularity: 0.8,
  /** Legacy profile-only blur threshold (NOT the active FRONT admission value). */
  profileMinLaplacianVariance: 80.0,
} as const)

/** Native `QualityReason` order (deterministic `sortedSetOf` ordinal order). */
export const QUALITY_REASONS = [
  'CARD_TOO_SMALL',
  'CROP_UNCERTAIN',
  'ANGLE_UNCERTAIN',
  'RESOLUTION_INSUFFICIENT',
  'TOO_BLURRY',
  'TOO_DARK',
  'GLARE_EXCESSIVE',
  'FEATURES_INSUFFICIENT',
] as const

export type QualityReason = (typeof QUALITY_REASONS)[number]
export type CaptureQualityProfile = 'front-admission' | 'legacy-profile'
export type CaptureQualitySide = 'front' | 'back'

export interface CaptureQualityMeter {
  /** Synchronous measurement over plain RGBA bytes. */
  measure(rgba: Uint8Array, width: number, height: number): CaptureQualitySignals
}

/**
 * Binds the synchronous measurement leaf to an already-loaded pinned runtime
 * (for later post-warp use). The runtime is validated at call time; this does
 * not load or cache anything itself.
 */
export function createCaptureQualityMeter(cv: QualityRawCv): CaptureQualityMeter {
  return Object.freeze({
    measure(rgba: Uint8Array, width: number, height: number): CaptureQualitySignals {
      return measureCaptureQualitySignals(cv, rgba, width, height)
    },
  })
}

export interface MeasureCaptureQualityOptions {
  readonly signal?: AbortSignal
  /** Optional current-owner fence; false/throw refuses with `stale`. */
  readonly fence?: () => boolean
  /** Test seam only; production default is the shared accepted pinned loader. */
  readonly loadModule?: (signal?: AbortSignal) => Promise<RawCv>
}

function checkGate(signal: AbortSignal | undefined, fence: (() => boolean) | undefined): void {
  if (signal?.aborted) throw new CaptureQualityError('cancelled')
  if (fence) {
    let current = false
    try {
      current = fence()
    } catch {
      current = false
    }
    if (!current) throw new CaptureQualityError('stale')
  }
}

/**
 * Async convenience wrapper: snapshot the caller bytes/dimensions BEFORE the
 * first await, load the shared pinned module, then run the synchronous leaf.
 * The native call is not interruptible; abort is checked before/after the load
 * and after the call. The caller's bytes are never mutated or wiped.
 */
export async function measureCaptureQuality(
  rgba: Uint8Array,
  width: number,
  height: number,
  options: MeasureCaptureQualityOptions = {},
): Promise<CaptureQualitySignals> {
  // Snapshot ALL consumed options synchronously BEFORE the first await. The
  // caller can replace `options.signal`/`fence`/`loadModule` while the loader
  // is pending; every pre/post-load/native/error path uses these captures only.
  const signal = options.signal
  const fence = options.fence
  const load = options.loadModule ?? loadOpenCvModule
  const dims = validateCaptureQualityDimensions(width, height)
  if (!(rgba instanceof Uint8Array) || rgba.length !== dims.width * dims.height * 4) {
    throw new CaptureQualityError('contract')
  }
  const ownedBytes = rgba.slice()
  try {
    checkGate(signal, fence)
    let cv: RawCv
    try {
      cv = await load(signal)
    } catch (error) {
      if (signal?.aborted) throw new CaptureQualityError('cancelled')
      if (error instanceof CaptureQualityError) throw error
      throw new CaptureQualityError('unavailable')
    }
    checkGate(signal, fence)
    const signals = measureCaptureQualitySignals(cv as unknown as QualityRawCv, ownedBytes, dims.width, dims.height)
    checkGate(signal, fence)
    return signals
  } finally {
    ownedBytes.fill(0)
  }
}

export interface CaptureQualityGateInput {
  readonly signals: CaptureQualitySignals
  readonly detectedAreaRatio: number
  readonly rectangularity: number
  /** Missing values are NOT invented; native skips the gate when absent. */
  readonly cropAspectRatio?: number | null
  readonly croppedShortEdgePx?: number | null
}

export interface CaptureQualityGateOptions {
  /** Active FRONT admission (110) by default; `legacy-profile` is 80. */
  readonly profile?: CaptureQualityProfile
  /** FRONT-only native contract; BACK is unsupported. */
  readonly side?: CaptureQualitySide
}

function validateSignals(signals: CaptureQualitySignals): CaptureQualitySignals {
  if (signals === null || typeof signals !== 'object') throw new CaptureQualityError('contract')
  const values: Array<readonly [keyof CaptureQualitySignals, number]> = [
    ['laplacianVariance', signals.laplacianVariance],
    ['nearBlackFraction', signals.nearBlackFraction],
    ['clippedWhiteFraction', signals.clippedWhiteFraction],
    ['largestGlareFraction', signals.largestGlareFraction],
  ]
  for (const [, value] of values) {
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new CaptureQualityError('contract')
  }
  for (const key of ['nearBlackFraction', 'clippedWhiteFraction', 'largestGlareFraction'] as const) {
    const value = signals[key]
    if (value < 0 || value > 1) throw new CaptureQualityError('contract')
  }
  return signals
}

/**
 * Pure advisory mirror of the native `CaptureQualityGate.evaluate`.
 * Returns the reason codes in native enum order; an empty list means the
 * measurement/geometry passed the quality gates. It is NOT an admission flag.
 */
export function evaluateCaptureQualityGate(
  input: CaptureQualityGateInput,
  options: CaptureQualityGateOptions = {},
): QualityReason[] {
  const side = options.side ?? 'front'
  if (side === 'back') throw new CaptureQualityError('contract')
  if (input === null || typeof input !== 'object') throw new CaptureQualityError('contract')
  const signals = validateSignals(input.signals)
  const profile = options.profile ?? 'front-admission'
  const gates = CAPTURE_QUALITY_GATES
  const minLaplacianVariance =
    profile === 'front-admission'
      ? FRONT_ADMISSION_MIN_LAPLACIAN_VARIANCE
      : gates.profileMinLaplacianVariance
  const reasons = new Set<QualityReason>()

  if (signals.laplacianVariance < minLaplacianVariance) reasons.add('TOO_BLURRY')
  if (signals.nearBlackFraction > gates.maxNearBlackFraction) reasons.add('TOO_DARK')
  if (
    signals.clippedWhiteFraction > gates.maxClippedWhiteFraction ||
    signals.largestGlareFraction > gates.maxGlareRegionFraction
  ) {
    reasons.add('GLARE_EXCESSIVE')
  }

  if (!Number.isFinite(input.detectedAreaRatio) || input.detectedAreaRatio < 0) {
    reasons.add('CROP_UNCERTAIN')
  } else if (input.detectedAreaRatio < gates.minCardAreaRatio) {
    reasons.add('CARD_TOO_SMALL')
  }

  if (!Number.isFinite(input.rectangularity) || input.rectangularity < 0) {
    reasons.add('CROP_UNCERTAIN')
  } else if (input.rectangularity < gates.minRectangularity) {
    reasons.add('ANGLE_UNCERTAIN')
  }

  if (input.cropAspectRatio !== undefined && input.cropAspectRatio !== null) {
    const ratio = input.cropAspectRatio
    if (!Number.isFinite(ratio) || ratio <= 0) {
      reasons.add('CROP_UNCERTAIN')
    } else {
      const orientationNormalizedRatio = ratio < 1 ? 1 / ratio : ratio
      if (!Number.isFinite(orientationNormalizedRatio)) {
        reasons.add('CROP_UNCERTAIN')
      } else if (
        !(orientationNormalizedRatio >= gates.aspectRatioMin && orientationNormalizedRatio <= gates.aspectRatioMax)
      ) {
        reasons.add('ANGLE_UNCERTAIN')
      }
    }
  }

  if (input.croppedShortEdgePx !== undefined && input.croppedShortEdgePx !== null) {
    const shortEdge = input.croppedShortEdgePx
    if (!Number.isFinite(shortEdge) || shortEdge <= 0) {
      reasons.add('CROP_UNCERTAIN')
    } else if (shortEdge < gates.minShortEdgeAfterWarpPx) {
      reasons.add('RESOLUTION_INSUFFICIENT')
    }
  }

  return QUALITY_REASONS.filter((reason) => reasons.has(reason))
}
