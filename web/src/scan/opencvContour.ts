/**
 * T316 — additive typed OpenCV port extension for the genuine sender
 * localization path (legacy contour detection + perspective warp). It reuses
 * the EXISTING same-origin cached runtime; no new asset, loader, CDN, model
 * or rebuild. The real implementation lives in `opencvRuntime.ts`
 * (`wrapOpenCvRuntime` returns this extended interface); tests supply a
 * deterministic fake. The existing SIFT-only `CvRuntime` and its fakes are
 * untouched — nothing here forces new stubs into SIFT-only tests.
 */

import type { CvMat, CvRuntime } from './opencvPort'
import type { Point2D } from './cornerGeometry'

/** One native approx-poly candidate before ordering (raw approx order). */
export interface CvDetectedQuad {
  readonly points: readonly [Point2D, Point2D, Point2D, Point2D]
  readonly quadArea: number
  readonly contourArea: number
}

/** Warp output owns fresh JS bytes (copied out before native release). */
export interface CvWarpedRgba {
  readonly bytes: Uint8Array
  readonly width: number
  readonly height: number
}

/**
 * Contour + warp extension over the accepted SIFT runtime. Every method that
 * allocates native objects releases the ones it owns before returning or
 * throwing; ownership transfer is documented per method.
 */
export interface CvContourRuntime extends CvRuntime {
  /** Owns a fresh CV_8UC4 Mat initialized from `bytes` (length width*height*4). */
  matFromRgba(bytes: Uint8Array, width: number, height: number): CvMat
  /**
   * Runs the real legacy contour pipeline (gray, 5x5 blur, mean-driven Canny,
   * 7x7 rectangular close, external/simple contours, 0.02-perimeter approx,
   * convex 4-point gate, loose area pre-filter) and returns plain candidate
   * data. Releases every intermediate native object. Does NOT release `rgba`.
   */
  detectQuadCandidates(rgba: CvMat, frameWidth: number, frameHeight: number): CvDetectedQuad[]
  /**
   * Real `getPerspectiveTransform` + `warpPerspective` of `rgba` onto
   * `dstWidth`x`dstHeight` using ordered `srcQuad` -> `dstQuad`. Returns a
   * fresh owned byte copy; releases transform/destination Mats. Does NOT
   * release `rgba`.
   */
  warpRgbaQuad(
    rgba: CvMat,
    srcQuad: readonly [Point2D, Point2D, Point2D, Point2D],
    dstQuad: readonly [Point2D, Point2D, Point2D, Point2D],
    dstWidth: number,
    dstHeight: number,
  ): CvWarpedRgba
}
