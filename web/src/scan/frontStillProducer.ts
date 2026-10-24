/**
 * T316 — genuine sender producer: FrontStill JPEG -> localized/warped ->
 * format-3 fingerprint wire.
 *
 * Starting from an explicit already-confirmed `FrontStill`, this decodes the
 * JPEG upright exactly once, exact-fits to the 1600 working long edge, runs
 * the REAL legacy contour detector, selects the best credible contour (or the
 * faithfully labelled bounded guide fallback), perspective-warps to canonical
 * 1600-long-edge RGBA, and feeds the ACCEPTED genuine SIFT extractor +
 * serializer. This is NOT camera capture, quality measurement, matching,
 * receive admission, or App/Send wiring.
 */

import { SOURCE_PHOTO_MAX_BYTES, SOURCE_PHOTO_MAX_EDGE_PX, sourceJpegDimensions } from '../create/normalize'
import type { FrontStill } from '../create/front'
import type { StillCapture } from './camera'
import { orderClockwiseFromTopLeft, type Point2D } from './cornerGeometry'
import { selectCrop, type CropCandidate, type ProposalSource } from './cropSelection'
import { planWarp, targetCorners } from './warpGeometry'
import { extractWithRuntime, rint, SiftExtractionError } from './siftExtractor'
import { serializeSiftFingerprint, SIFT_FINGERPRINT_PROFILE_ID, S3_FINGERPRINT_PROFILE_ID, type SiftFingerprint } from './siftFingerprint'
import * as S3 from './s3/frozenMatcher'
import type { Localizer } from './s3/frozenMatcher'
import { loadFrozenMatcher, type FrozenMatcherAssets } from './s3/s3Assets'
import { toFingerprint as toS3Fingerprint } from './s3/s3Fingerprint'
import {
  createCaptureQualityMeter,
  evaluateCaptureQualityGate,
  type CaptureQualityMeter,
  type QualityReason,
} from './captureQuality'
import {
  CaptureQualityError,
  type CaptureQualitySignals,
  type QualityRawCv,
} from './opencvQualityPort'
import { loadOpenCvModule, loadOpenCvRuntime, wrapOpenCvRuntime } from './opencvRuntime'
import type { CvContourRuntime, CvDetectedQuad } from './opencvContour'

export const FRONT_PRODUCER_WORKING_LONG_EDGE_PX = 1600

export type FrontStillErrorCode =
  | 'dimensions'
  | 'pixels'
  | 'stale'
  | 'cancelled'
  | 'decode'
  | 'crop'
  | 'warp'
  | 'features'
  | 'unavailable'
  | 'internal'

/** Bounded, redacted producer refusal. Never pixel/JPEG/descriptor data. */
export class FrontStillError extends Error {
  constructor(readonly code: FrontStillErrorCode) {
    super(code)
    this.name = 'FrontStillError'
  }
}

function refuse(code: FrontStillErrorCode): FrontStillError {
  return new FrontStillError(code)
}

export interface FrontStillRecipient {
  readonly userId: string
  readonly keyBundleId: string
  readonly directoryVersion: string
}

export interface CropProvenance {
  readonly source: ProposalSource
  readonly corners: readonly [Point2D, Point2D, Point2D, Point2D]
  readonly areaRatio: number
  readonly rectangularity: number
  readonly confidence: number | null
  readonly workingWidth: number
  readonly workingHeight: number
  readonly warpedWidth: number
  readonly warpedHeight: number
}

/** Copied post-warp quality diagnostics (scalars + advisory reasons only). */
export interface CaptureQualityDiagnostics {
  readonly signals: CaptureQualitySignals
  readonly reasons: readonly QualityReason[]
}

/** Identity-free localised fingerprint (shared core output). */
export interface LocalizedFingerprint {
  /** Owned canonical format-3 wire bytes (transferred, not wiped). */
  readonly wire: Uint8Array
  readonly profileId: string
  readonly provenance: CropProvenance
  /** Present only for a query that explicitly requested post-warp quality. */
  readonly quality?: CaptureQualityDiagnostics
}

/** Existing sender output: localised fingerprint plus the captured recipient. */
export interface FrontStillFingerprint extends LocalizedFingerprint {
  readonly recipient: FrontStillRecipient
}

/** Decoded upright RGBA frame (owned pixels). */
export interface OwnedRgbaFrame {
  readonly rgba: Uint8Array
  readonly width: number
  readonly height: number
}

export interface ProduceFrontStillOptions {
  /** REQUIRED zero-argument current-visit fence; gets no pixels/keys/bytes. */
  readonly fence: () => boolean
  readonly signal?: AbortSignal
  /** Test seam only; the production path always loads the real pinned runtime. */
  readonly runtime?: CvContourRuntime | Promise<CvContourRuntime>
  /**
   * Test seam only; defaults to the real DOM decode. A promise is accepted so
   * a bounded test can suspend the load/decode.
   */
  readonly decode?: (jpeg: Uint8Array, signal?: AbortSignal) => Promise<OwnedRgbaFrame>
  /** Receiving-query opt-in: measure exact post-warp quality (advisory only). */
  readonly quality?: boolean
  /** Test seam only; production default is created from the shared cached RawCv. */
  readonly meter?: CaptureQualityMeter
}

function throwIfFence(fence: () => boolean): void {
  let current = false
  try {
    current = fence()
  } catch {
    current = false
  }
  if (!current) throw refuse('stale')
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) throw refuse('cancelled')
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function captureRecipient(still: FrontStill): FrontStillRecipient {
  if (!still || typeof still !== 'object') throw refuse('dimensions')
  if (!isNonEmptyString(still.userId) || !isNonEmptyString(still.keyBundleId) || !isNonEmptyString(still.directoryVersion)) {
    throw refuse('dimensions')
  }
  return { userId: still.userId, keyBundleId: still.keyBundleId, directoryVersion: still.directoryVersion }
}

/** Validates the JPEG source bounds and returns an owned copy, pre-await. */
function captureJpegBytes(source: StillCapture): Uint8Array {
  const bytes = (source as { bytes?: unknown }).bytes
  if (!(bytes instanceof Uint8Array) || bytes.length === 0 || bytes.length > SOURCE_PHOTO_MAX_BYTES) {
    throw refuse('pixels')
  }
  const stored = sourceJpegDimensions(bytes)
  if (!stored) throw refuse('decode')
  if (!Number.isInteger(stored.width) || !Number.isInteger(stored.height) || stored.width < 1 || stored.height < 1) {
    throw refuse('decode')
  }
  if (stored.width > SOURCE_PHOTO_MAX_EDGE_PX || stored.height > SOURCE_PHOTO_MAX_EDGE_PX) throw refuse('decode')
  const owned = new Uint8Array(bytes.length)
  owned.set(bytes)
  return owned
}

/** Exact-fit working size: long edge <= 1600, never upscale, ties-even. */
export function exactFitWorkingSize(uprightWidth: number, uprightHeight: number): { width: number; height: number } {
  if (!Number.isInteger(uprightWidth) || !Number.isInteger(uprightHeight) || uprightWidth < 1 || uprightHeight < 1) {
    throw refuse('decode')
  }
  if (uprightWidth > SOURCE_PHOTO_MAX_EDGE_PX || uprightHeight > SOURCE_PHOTO_MAX_EDGE_PX) throw refuse('decode')
  const longEdge = Math.max(uprightWidth, uprightHeight)
  const scale = longEdge <= FRONT_PRODUCER_WORKING_LONG_EDGE_PX ? 1 : FRONT_PRODUCER_WORKING_LONG_EDGE_PX / longEdge
  const width = Math.max(1, rint(uprightWidth * scale))
  const height = Math.max(1, rint(uprightHeight * scale))
  return { width, height }
}

/**
 * Real DOM decode: `createImageBitmap` with `imageOrientation: 'from-image'`
 * applies EXIF exactly once, then one canvas downscale reads upright pixels.
 * A malformed source refuses `decode`; the bitmap is always closed.
 */
export async function decodeJpegUpright(doc: Document, jpeg: Uint8Array): Promise<OwnedRgbaFrame> {
  const copy = jpeg.slice()
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(new Blob([copy.buffer as ArrayBuffer], { type: 'image/jpeg' }), {
      imageOrientation: 'from-image',
    })
  } catch {
    copy.fill(0)
    throw refuse('decode')
  }
  copy.fill(0)
  try {
    if (!Number.isInteger(bitmap.width) || !Number.isInteger(bitmap.height) || bitmap.width < 1 || bitmap.height < 1) {
      throw refuse('decode')
    }
    const target = exactFitWorkingSize(bitmap.width, bitmap.height)
    const canvas = doc.createElement('canvas')
    canvas.width = target.width
    canvas.height = target.height
    try {
      const context = canvas.getContext('2d')
      if (!context) throw refuse('decode')
      context.drawImage(bitmap, 0, 0, target.width, target.height)
      const imageData = context.getImageData(0, 0, target.width, target.height)
      const rgba = new Uint8Array(imageData.data)
      // The exposed ImageData buffer is wiped once its pixels are copied;
      // only the independently owned copy transfers out.
      imageData.data.fill(0)
      if (rgba.length !== target.width * target.height * 4) {
        rgba.fill(0)
        throw refuse('decode')
      }
      return { rgba, width: target.width, height: target.height }
    } finally {
      canvas.width = 0
    }
  } finally {
    try {
      bitmap.close()
    } catch {
      /* best-effort bitmap release */
    }
  }
}

function defaultDecode(jpeg: Uint8Array): Promise<OwnedRgbaFrame> {
  const doc = (globalThis as { document?: Document }).document
  if (!doc) throw refuse('decode')
  return decodeJpegUpright(doc, jpeg)
}

/** Orders raw native approx output; skips degenerate contours (never throws). */
function orderDetected(
  detected: readonly CvDetectedQuad[],
  frameWidth: number,
  frameHeight: number,
): CropCandidate[] {
  const frameArea = frameWidth * frameHeight
  const out: CropCandidate[] = []
  for (const quad of detected) {
    try {
      if (!(quad.quadArea > 0) || !Number.isFinite(quad.quadArea) || !Number.isFinite(quad.contourArea)) continue
      const ordered = orderClockwiseFromTopLeft(quad.points)
      out.push({
        corners: [ordered[0]!, ordered[1]!, ordered[2]!, ordered[3]!],
        areaRatio: quad.quadArea / frameArea,
        rectangularity: quad.contourArea / quad.quadArea,
      })
    } catch {
      /* degenerate approx output is skipped, never fatal */
    }
  }
  return out
}

function mapNativeError(stage: 'detect' | 'warp' | 'extract', error: unknown, fence: () => boolean, signal?: AbortSignal): FrontStillError {
  if (signal?.aborted) return refuse('cancelled')
  try {
    if (!fence()) return refuse('stale')
  } catch {
    return refuse('stale')
  }
  if (error instanceof SiftExtractionError) {
    if (error.code === 'no-features') return refuse('features')
    if (error.code === 'unavailable') return refuse('unavailable')
    return refuse('internal')
  }
  if (stage === 'detect') return refuse('crop')
  if (stage === 'warp') return refuse('warp')
  return refuse('unavailable')
}

function mapQualityError(
  error: unknown,
  fence: () => boolean,
  signal: AbortSignal | undefined,
): FrontStillError {
  if (signal?.aborted) return refuse('cancelled')
  try {
    if (!fence()) return refuse('stale')
  } catch {
    return refuse('stale')
  }
  if (error instanceof CaptureQualityError) {
    if (error.code === 'unavailable') return refuse('unavailable')
    return refuse('internal')
  }
  return refuse('internal')
}

/**
 * Genuine producer entry point. Captures the owner tuple, validated JPEG
 * bytes and source bounds synchronously before the first await; never
 * re-reads caller frame metadata afterwards. Rechecks fence/signal before
 * and after every await and native stage and before output transfer.
 */
export async function produceFrontStillFingerprint(
  still: FrontStill,
  options: ProduceFrontStillOptions,
): Promise<FrontStillFingerprint> {
  const fence = options.fence
  if (typeof fence !== 'function') throw refuse('stale')
  const signal = options.signal
  // Capture the recipient tuple and owned JPEG before the first await.
  const recipient = captureRecipient(still)
  const jpeg = captureJpegBytes(still)
  // Sender NEVER measures or returns query quality diagnostics, even when a
  // caller passes the shared `quality`/`meter` shape.
  const localized = await produceLocalizedFromOwnedJpeg(jpeg, {
    ...options,
    quality: false,
    meter: undefined,
  }, 'sender')
  // The core's promise boundary is an await: recheck the original fence and
  // signal before attaching caller owner metadata, wiping the transferred
  // wire if the visit changed or was cancelled in between.
  try {
    throwIfFence(fence)
    throwIfAborted(signal)
  } catch (error) {
    localized.wire.fill(0)
    throw error
  }
  return { ...localized, recipient }
}

/**
 * Identity-free shared core entry: a plain camera `StillCapture` (bytes only;
 * upright dimensions come from the real decode, never caller metadata) yields
 * an owned canonical format-3 wire/profile/provenance with no recipient fields.
 */
export async function produceLocalizedFingerprint(
  source: StillCapture,
  options: ProduceFrontStillOptions,
): Promise<LocalizedFingerprint> {
  const fence = options.fence
  if (typeof fence !== 'function') throw refuse('stale')
  // Capture the original owner fence/signal before any await.
  const signal = options.signal
  const jpeg = captureJpegBytes(source)
  const localized = await produceLocalizedFromOwnedJpeg(jpeg, options, 'recipient')
  // The core promise boundary is an await. Recheck the ORIGINAL captured
  // fence/signal at this output handover and wipe the transferred wire if the
  // owner went stale or the visit was cancelled in between (identity-free
  // query has no sender wrapper to perform this recheck).
  try {
    throwIfFence(fence)
    throwIfAborted(signal)
  } catch (error) {
    localized.wire.fill(0)
    throw error
  }
  return localized
}

/** Localize -> warp -> SIFT -> wire over an owned JPEG copy (identity-free). */
async function produceLocalizedFromOwnedJpeg(
  jpeg: Uint8Array,
  options: ProduceFrontStillOptions,
  role: FrozenMatcherRole,
): Promise<LocalizedFingerprint> {
  const fence = options.fence
  const signal = options.signal
  // Snapshot ALL consumed options BEFORE the first await; callers must not be
  // able to swap the quality flag/meter/runtime/decode while loading.
  const runtimeOption = options.runtime
  const decode = options.decode ?? defaultDecode
  const qualityRequested = options.quality === true
  const meterOption = options.meter ?? null
  try {
    throwIfFence(fence)
    throwIfAborted(signal)
    let frame: OwnedRgbaFrame
    try {
      frame = await decode(jpeg, signal)
    } catch (error) {
      if (signal?.aborted) throw refuse('cancelled')
      if (error instanceof FrontStillError) throw error
      throw refuse('decode')
    }
    try {
      if (
        !(frame instanceof Object) ||
        !(frame.rgba instanceof Uint8Array) ||
        !Number.isInteger(frame.width) ||
        !Number.isInteger(frame.height) ||
        frame.width < 1 ||
        frame.height < 1 ||
        frame.width > SOURCE_PHOTO_MAX_EDGE_PX ||
        frame.height > SOURCE_PHOTO_MAX_EDGE_PX ||
        frame.rgba.length !== frame.width * frame.height * 4
      ) {
        throw refuse('decode')
      }
      throwIfFence(fence)
      throwIfAborted(signal)
      // Production fingerprints come from the frozen postcard matcher (S3);
      // the legacy contour/warp path remains behind the runtime test seam.
      if (runtimeOption === undefined) return await extractFrozenMatcher(frame, role, fence, signal)
      let runtime: CvContourRuntime
      // A supplied meter is only active with the captured explicit opt-in.
      // quality:false/absent must never measure (default behavior unchanged).
      let meter: CaptureQualityMeter | null = qualityRequested ? meterOption : null
      try {
        if (runtimeOption !== undefined) {
          runtime = await runtimeOption
          // An isolated runtime seam must be paired with an explicit meter
          // seam; never fabricate metrics or silently ignore the request.
          if (qualityRequested && meter === null) throw refuse('internal')
        } else if (qualityRequested) {
          const cv = await loadOpenCvModule(signal)
          runtime = wrapOpenCvRuntime(cv)
          if (meter === null) meter = createCaptureQualityMeter(cv as unknown as QualityRawCv)
        } else {
          runtime = await loadOpenCvRuntime(signal)
        }
      } catch (error) {
        if (signal?.aborted) throw refuse('cancelled')
        if (error instanceof FrontStillError) throw error
        throw refuse('unavailable')
      }
      throwIfFence(fence)
      throwIfAborted(signal)
      return extractLocalized(runtime, frame, fence, signal, meter)
    } finally {
      frame.rgba.fill(0)
    }
  } finally {
    jpeg.fill(0)
  }
}

type FrozenMatcherRole = 'sender' | 'recipient'

/**
 * Frozen postcard matcher fingerprint over an owned working frame: the sender
 * describes the GrabCut-cropped card; the recipient query describes the card
 * the ONNX localizer finds inside the centred 2:3 frame.
 */
async function extractFrozenMatcher(
  frame: OwnedRgbaFrame,
  role: FrozenMatcherRole,
  fence: () => boolean,
  signal?: AbortSignal,
): Promise<LocalizedFingerprint> {
  let matcher: FrozenMatcherAssets
  let localizer: Localizer | null = null
  try {
    matcher = await loadFrozenMatcher(signal)
    if (role === 'recipient') localizer = await matcher.localizer()
  } catch {
    if (signal?.aborted) throw refuse('cancelled')
    throw refuse('unavailable')
  }
  throwIfFence(fence)
  throwIfAborted(signal)
  const cv = matcher.cv
  const bgr = S3.bgr960(cv, frame.rgba, frame.width, frame.height)
  let fingerprint: SiftFingerprint
  let features: S3.Features
  try {
    if (role === 'sender') {
      features = S3.senderFeatures(cv, bgr)
      if (S3.featureCount(features) < 12) throw refuse('features')
      fingerprint = toS3Fingerprint(features)
    } else {
      const [x0, y0, x1, y1] = S3.frameBounds(bgr.cols, bgr.rows)
      const framed = bgr.roi(new cv.Rect(x0, y0, x1 - x0, y1 - y0)).clone()
      try {
        const recipient = S3.recipientFeatures(cv, framed, localizer!.mask(framed))
        features = recipient.features
        if (S3.featureCount(features) < 1) throw refuse('features')
        fingerprint = toS3Fingerprint(features, {
          originX: features.originX, originY: features.originY,
          frameWidth: features.frameWidth, frameHeight: features.frameHeight, components: recipient.components,
        })
      } finally {
        framed.delete()
      }
    }
  } catch (error) {
    if (error instanceof FrontStillError) throw error
    throw refuse('features')
  } finally {
    bgr.delete()
  }
  throwIfFence(fence)
  throwIfAborted(signal)
  const wire = serializeSiftFingerprint(fingerprint)
  for (const row of fingerprint.quantizedSiftDescriptors) row.fill(0)
  return {
    wire,
    profileId: S3_FINGERPRINT_PROFILE_ID,
    provenance: {
      source: 'GUIDE_FALLBACK',
      corners: [{ x: 0, y: 0 }, { x: frame.width, y: 0 }, { x: frame.width, y: frame.height }, { x: 0, y: frame.height }],
      areaRatio: 1,
      rectangularity: 1,
      confidence: null,
      workingWidth: frame.width,
      workingHeight: frame.height,
      warpedWidth: features.width,
      warpedHeight: features.height,
    },
  }
}

/** Localize -> warp -> SIFT -> wire over an owned working frame. Synchronous. */
function extractLocalized(
  runtime: CvContourRuntime,
  frame: OwnedRgbaFrame,
  fence: () => boolean,
  signal: AbortSignal | undefined,
  meter: CaptureQualityMeter | null,
): LocalizedFingerprint {
  const releases: Array<() => void> = []
  const runReleases = (strict: boolean): unknown => {
    let first: unknown = null
    while (releases.length > 0) {
      const release = releases.pop()!
      try {
        release()
      } catch (error) {
        if (first === null) first = error
      }
    }
    if (strict && first !== null) throw first
    return first
  }
  const wipeMat = (mat: { type(): number; data: Uint8Array; data32F: Float32Array; delete(): void }): void => {
    try {
      if (mat.type() === runtime.CV_8UC1) mat.data.fill(0)
      else if (mat.type() === runtime.CV_32F) mat.data32F.fill(0)
      else mat.data.fill(0)
    } catch {
      /* wiping is best-effort; delete below must still run */
    }
    mat.delete()
  }
  let warpedBytes: Uint8Array | null = null
  let domainRows: Uint8Array[] | null = null
  // The freshly serialized wire is owned from allocation until transfer; a
  // refusal between serialization and transfer must wipe it (see `finally`).
  let wireBytes: Uint8Array | null = null
  let cleanupAttempted = false
  let returned = false
  try {
    const rgba = runtime.matFromRgba(frame.rgba, frame.width, frame.height)
    releases.push(() => wipeMat(rgba))
    throwIfFence(fence)
    throwIfAborted(signal)
    let detected
    try {
      detected = runtime.detectQuadCandidates(rgba, frame.width, frame.height)
    } catch (error) {
      throw mapNativeError('detect', error, fence, signal)
    }
    throwIfFence(fence)
    throwIfAborted(signal)
    const selection = selectCrop(orderDetected(detected, frame.width, frame.height), frame.width, frame.height)
    let plan
    try {
      plan = planWarp(selection.candidate.corners)
    } catch {
      throw refuse('warp')
    }
    throwIfFence(fence)
    throwIfAborted(signal)
    let warped
    try {
      warped = runtime.warpRgbaQuad(rgba, plan.source, targetCorners(plan.targetWidth, plan.targetHeight), plan.targetWidth, plan.targetHeight)
    } catch (error) {
      throw mapNativeError('warp', error, fence, signal)
    }
    warpedBytes = warped.bytes
    throwIfFence(fence)
    throwIfAborted(signal)
    // Exact post-warp measurement, once, before SIFT. Warnings stay advisory.
    let quality: CaptureQualityDiagnostics | null = null
    if (meter !== null) {
      const measured = (() => {
        try {
          const signals = meter.measure(warped.bytes, warped.width, warped.height)
          return {
            signals,
            reasons: evaluateCaptureQualityGate({
              signals,
              detectedAreaRatio: selection.candidate.areaRatio,
              rectangularity: selection.candidate.rectangularity,
              cropAspectRatio: warped.width / warped.height,
              croppedShortEdgePx: Math.min(warped.width, warped.height),
            }),
          }
        } catch (error) {
          throw mapQualityError(error, fence, signal)
        }
      })()
      throwIfFence(fence)
      throwIfAborted(signal)
      quality = { signals: { ...measured.signals }, reasons: [...measured.reasons] }
    }
    let fingerprint
    try {
      fingerprint = extractWithRuntime(runtime, warped.bytes, warped.width, warped.height, signal)
    } catch (error) {
      throw mapNativeError('extract', error, fence, signal)
    }
    domainRows = [...fingerprint.quantizedSiftDescriptors]
    warped.bytes.fill(0)
    warpedBytes = null
    throwIfFence(fence)
    throwIfAborted(signal)
    // Native cleanup must complete before the wire/rows can transfer. A
    // cleanup failure refuses typed `internal`; the still-owned rows are
    // wiped by the `finally` below and no output is transferred.
    cleanupAttempted = true
    try {
      runReleases(true)
    } catch {
      throw refuse('internal')
    }
    const wire = serializeSiftFingerprint(fingerprint)
    wireBytes = wire
    for (const row of domainRows) row.fill(0)
    domainRows = null
    throwIfFence(fence)
    throwIfAborted(signal)
    returned = true
    const provenance = {
      source: selection.source,
      corners: selection.candidate.corners,
      areaRatio: selection.candidate.areaRatio,
      rectangularity: selection.candidate.rectangularity,
      confidence: selection.confidence,
      workingWidth: frame.width,
      workingHeight: frame.height,
      warpedWidth: warped.width,
      warpedHeight: warped.height,
    }
    const result: LocalizedFingerprint = {
      wire,
      profileId: SIFT_FINGERPRINT_PROFILE_ID,
      provenance,
      ...(quality === null ? {} : { quality }),
    }
    warpedBytes = null
    return result
  } catch (error) {
    throw error
  } finally {
    if (warpedBytes) warpedBytes.fill(0)
    if (!returned && wireBytes) wireBytes.fill(0)
    if (!cleanupAttempted) {
      cleanupAttempted = true
      runReleases(false)
    }
    if (!returned && domainRows) {
      for (const row of domainRows) row.fill(0)
    }
  }
}
